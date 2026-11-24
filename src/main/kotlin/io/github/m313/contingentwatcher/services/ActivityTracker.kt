package io.github.m313.contingentwatcher.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.editor.event.SelectionListener
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.IdeFrame
import com.intellij.util.concurrency.AppExecutorUtil
import io.github.m313.contingentwatcher.core.ActivityBook
import io.github.m313.contingentwatcher.core.ActivityItem
import io.github.m313.contingentwatcher.core.ActivityKey
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Repository and branch of a file. */
data class BranchInfo(val repoName: String, val branch: String, val rootPath: String, val commit: String?)

/** Implemented by the optional Git integration (Git4Idea). */
interface BranchProvider {
    fun branchInfo(project: Project, file: VirtualFile?): BranchInfo?

    /** First line of the commit message (may run git; call off the EDT). */
    fun commitSubject(project: Project, info: BranchInfo): String?

    companion object {
        val EP: ExtensionPointName<BranchProvider> = ExtensionPointName.create("io.github.m313.contingentwatcher.branchProvider")
    }
}

/**
 * Records active editor time and quota usage per git branch. "Active" means the
 * IDE is focused and there was editor activity in the last 5 minutes.
 * Everything stays local.
 */
@Service(Service.Level.APP)
class ActivityTracker : Disposable {
    @Volatile
    private var lastSignal = System.currentTimeMillis()
    private var timer: ScheduledFuture<*>? = null
    private val detailCache = ConcurrentHashMap<String, String>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val store get() = ContingentStore.instance

    init {
        val signal = { lastSignal = System.currentTimeMillis() }
        val multicaster = EditorFactory.getInstance().eventMulticaster
        multicaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (FileDocumentManager.getInstance().getFile(event.document)?.isInLocalFileSystem == true) signal()
            }
        }, this)
        multicaster.addCaretListener(object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) = signal()
        }, this)
        multicaster.addSelectionListener(object : SelectionListener {
            override fun selectionChanged(e: SelectionEvent) = signal()
        }, this)
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            ApplicationActivationListener.TOPIC,
            object : ApplicationActivationListener {
                override fun applicationActivated(ideFrame: IdeFrame) = signal()
            },
        )
        setEnabled(ContingentSettings.instance.state.activityEnabled)
    }

    fun addListener(l: () -> Unit) {
        listeners.add(l)
    }

    fun setEnabled(enabled: Boolean) {
        timer?.cancel(false)
        timer = null
        if (enabled) {
            timer = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay({ tick() }, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS)
        }
    }

    private val enabled get() = ContingentSettings.instance.state.activityEnabled

    /** The project the user works in: the last focused frame's project. */
    fun currentProject(): Project? =
        IdeFocusManager.getGlobalInstance().lastFocusedFrame?.project?.takeIf { !it.isDisposed }
            ?: ProjectManager.getInstance().openProjects.firstOrNull { !it.isDisposed }

    private fun branchInfo(project: Project): BranchInfo? {
        val file = ApplicationManager.getApplication().runReadAction<VirtualFile?> {
            if (project.isDisposed) null else FileEditorManager.getInstance(project).selectedFiles.firstOrNull()
        }
        for (p in BranchProvider.EP.extensionList) {
            try {
                p.branchInfo(project, file)?.let { return it }
            } catch (_: Throwable) {
            }
        }
        return headFileBranch(project)
    }

    /** Fallback without the Git plugin: reads .git/HEAD of the project folder. */
    private fun headFileBranch(project: Project): BranchInfo? {
        val base = project.basePath ?: return null
        val head = File(base, ".git/HEAD").takeIf { it.isFile } ?: return null
        val text = runCatching { head.readText().trim() }.getOrNull() ?: return null
        val branch = if (text.startsWith("ref:")) text.removePrefix("ref:").trim().removePrefix("refs/heads/") else "HEAD"
        return BranchInfo(File(base).name, branch, base, if (text.startsWith("ref:")) null else text)
    }

    /** Last key computed off the EDT (Git lookups must not run on the UI thread). */
    @Volatile
    private var cachedKey: ActivityKey? = null

    /** The branch/repo currently worked on. Call off the EDT. */
    fun currentKey(): ActivityKey? {
        val project = currentProject() ?: return null
        val info = branchInfo(project)
        val key = if (info != null) ActivityKey(info.repoName, info.branch) else ActivityKey(project.name)
        if (key != cachedKey) {
            cachedKey = key
            fire()
        }
        return key
    }

    private fun detail(project: Project): String? {
        val info = branchInfo(project) ?: return null
        val commit = info.commit ?: return null
        detailCache[commit]?.let { return it }
        for (p in BranchProvider.EP.extensionList) {
            val s = try {
                p.commitSubject(project, info)
            } catch (_: Throwable) {
                null
            }
            if (s != null) {
                detailCache[commit] = s.lineSequence().first().take(120)
                return detailCache[commit]
            }
        }
        return null
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        if (!enabled || !ApplicationManager.getApplication().isActive || now - lastSignal > ActivityBook.IDLE_MS) return
        val project = currentProject() ?: return
        val key = currentKey() ?: return
        synchronized(this) {
            store.activity = ActivityBook.addActiveTime(store.activity, key, now, TICK_MS, detail(project))
        }
        fire()
    }

    /** Attributes newly used quota to the current item. */
    fun recordUsage(delta: Double) {
        val key = currentKey()
        // Only attribute if the user was actually working recently.
        if (!enabled || key == null || delta <= 0 || System.currentTimeMillis() - lastSignal > 4 * ActivityBook.IDLE_MS) return
        synchronized(this) {
            store.activity = ActivityBook.addUsage(store.activity, key, System.currentTimeMillis(), delta)
        }
        fire()
    }

    fun clear() {
        store.activity = emptyList()
        fire()
    }

    private fun fire() = listeners.forEach { runCatching { it() } }

    /** Items and the "active" check. Safe on the EDT: uses the cached key and refreshes it in the background. */
    fun snapshot(): Pair<List<ActivityItem>, (ActivityItem) -> Boolean> {
        val key = cachedKey
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                currentKey()
            } catch (_: Throwable) {
            }
        }
        val now = System.currentTimeMillis()
        return store.activity to { i: ActivityItem -> ActivityBook.isActive(i, key, now) }
    }

    override fun dispose() {
        timer?.cancel(false)
    }

    companion object {
        private const val TICK_MS = 30_000L

        val instance: ActivityTracker
            get() = ApplicationManager.getApplication().getService(ActivityTracker::class.java)
    }
}
