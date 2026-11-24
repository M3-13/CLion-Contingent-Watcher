package io.github.m313.contingentwatcher.btw

import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.LanguageUtil
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtilCore
import io.github.m313.contingentwatcher.l10n.L10n
import io.github.m313.contingentwatcher.services.ContingentLog
import io.github.m313.contingentwatcher.services.ContingentSettings
import io.github.m313.contingentwatcher.services.ContingentStore
import io.github.m313.contingentwatcher.services.CopilotTokens
import java.io.File

/**
 * Answers one btw side question into a card of the btw window: gathers the
 * context (editor, problems, best-effort chat snapshot), picks the model and
 * streams the answer.
 */
object BtwRunner {
    private const val CURSOR_RADIUS = 30
    private const val MAX_EDITOR_CHARS = 8000
    private const val MAX_LINE_CHARS = 400
    private const val MAX_DIAGNOSTICS = 20
    private const val MAX_SESSION_BYTES = 64L * 1024 * 1024
    private const val MAX_SESSION_AGE_MS = 24L * 60 * 60 * 1000
    private const val SESSION_CANDIDATES = 8
    private val SKIP = ChatSessionParse.Options(skipAgentIds = listOf("contingentWatcher.btw"), skipPrefixes = listOf("@btw"))
    private val IGNORED_FILES = setOf("apps.json", "hosts.json", "versions.json", "settings.json")

    private val log get() = ContingentLog.instance

    fun contextLabels() = ContextLabels(
        chatOne = L10n.t("chat (1 turn)"),
        chatMany = L10n.t("chat ({0} turns)", "{0}"),
        selection = L10n.t("editor selection in {0}", "{0}"),
        cursor = L10n.t("editor around line {0} in {1}", "{0}", "{1}"),
        problemOne = L10n.t("1 problem"),
        problemMany = L10n.t("{0} problems", "{0}"),
        referenceOne = L10n.t("1 reference"),
        referenceMany = L10n.t("{0} references", "{0}"),
        none = L10n.t("none"),
    )

    // ------------------------------------------------------------------ editor

    /** Snapshot of the selected editor (selection, else lines around the caret) and its problems. Call on the EDT. */
    fun editorSnapshot(project: Project?, editorOverride: Editor? = null): EditorSnapshot? {
        project ?: return null
        val editor = editorOverride ?: FileEditorManager.getInstance(project).selectedTextEditor ?: return null
        return ApplicationManager.getApplication().runReadAction<EditorSnapshot?> {
            val doc = editor.document
            val file = FileDocumentManager.getInstance().getFile(doc) ?: return@runReadAction null
            val sel = editor.selectionModel
            val caretLine = doc.getLineNumber(editor.caretModel.offset)
            val kind: String
            val startOffset: Int
            val endOffset: Int
            if (sel.hasSelection()) {
                kind = "selection"
                startOffset = sel.selectionStart
                endOffset = sel.selectionEnd
            } else {
                kind = "cursor"
                if (doc.lineCount == 0) return@runReadAction null
                val start = maxOf(0, caretLine - CURSOR_RADIUS)
                val end = minOf(doc.lineCount - 1, caretLine + CURSOR_RADIUS)
                startOffset = doc.getLineStartOffset(start)
                endOffset = doc.getLineEndOffset(end)
            }
            var text = doc.charsSequence.subSequence(startOffset, endOffset).toString()
            if (kind == "cursor" && text.isBlank()) return@runReadAction null
            text = text.split(Regex("\r?\n")).joinToString("\n") { if (it.length > MAX_LINE_CHARS) it.take(MAX_LINE_CHARS) + " …" else it }
            if (text.length > MAX_EDITOR_CHARS) text = text.take(MAX_EDITOR_CHARS) + "\n… [shortened]"

            val diags = ArrayList<Pair<HighlightInfo, Int>>()
            try {
                DaemonCodeAnalyzerEx.processHighlights(doc, project, HighlightSeverity.WARNING, startOffset, endOffset) { info ->
                    if (info.description != null) diags.add(info to doc.getLineNumber(info.startOffset.coerceIn(0, doc.textLength)))
                    true
                }
            } catch (_: Throwable) {
            }
            val diagnostics = diags
                .sortedWith(compareByDescending<Pair<HighlightInfo, Int>> { it.first.severity }.thenBy { it.second })
                .distinctBy { it.second to it.first.description }
                .take(MAX_DIAGNOSTICS)
                .map { (info, line) ->
                    val msg = info.description ?: ""
                    EditorDiagnostic(
                        line = line + 1,
                        severity = if (info.severity >= HighlightSeverity.ERROR) "error" else "warning",
                        message = if (msg.length > 300) msg.take(300) + "…" else msg,
                        source = info.inspectionToolId,
                    )
                }
            val base = project.guessProjectDir()
            val path = base?.let { VfsUtilCore.getRelativePath(file, it) } ?: file.name
            val language = languageId(file.extension) ?: (LanguageUtil.getFileLanguage(file)?.id ?: file.fileType.name).lowercase()
            EditorSnapshot(
                path = path,
                languageId = language,
                kind = kind,
                startLine = doc.getLineNumber(startOffset) + 1,
                endLine = doc.getLineNumber(endOffset) + 1,
                cursorLine = caretLine + 1,
                text = text,
                diagnostics = diagnostics,
            )
        }
    }

    /** Markdown fence language from the file extension (CLion reports C/C++ as "ObjectiveC"). */
    private fun languageId(ext: String?): String? = when (ext?.lowercase()) {
        "c", "h" -> "c"
        "cpp", "cc", "cxx", "c++", "hpp", "hh", "hxx", "h++", "ipp", "tpp", "inl", "ixx", "cppm" -> "cpp"
        "m" -> "objective-c"
        "mm" -> "objective-cpp"
        "cu", "cuh" -> "cuda"
        "cmake" -> "cmake"
        "py" -> "python"
        "rs" -> "rust"
        "kt", "kts" -> "kotlin"
        "java" -> "java"
        "js", "mjs", "cjs" -> "javascript"
        "ts", "mts", "cts" -> "typescript"
        "go" -> "go"
        "swift" -> "swift"
        "sh", "bash", "zsh" -> "bash"
        "json" -> "json"
        "yml", "yaml" -> "yaml"
        "md" -> "markdown"
        else -> null
    }

    // ------------------------------------------------------------------ chat

    /** Folders searched for local chat session files (JSON / JSONL). */
    private fun sessionDirs(): List<File> {
        val out = ArrayList<File>()
        ContingentSettings.instance.state.btwChatSessionDir.trim().takeIf { it.isNotEmpty() }?.let { out.add(File(it)) }
        out.add(CopilotTokens.configDir())
        return out.filter { it.isDirectory }
    }

    private fun listSessionFiles(dir: File): List<File> {
        val out = ArrayList<File>()
        val cutoff = System.currentTimeMillis() - MAX_SESSION_AGE_MS
        dir.walkTopDown().maxDepth(5).onEnter { !it.name.startsWith(".") && it.name != "node_modules" }.forEach { f ->
            if (out.size < 2000 && f.isFile && f.name !in IGNORED_FILES && (f.name.endsWith(".json", true) || f.name.endsWith(".jsonl", true)) && f.lastModified() >= cutoff) {
                out.add(f)
            }
        }
        return out.sortedByDescending { it.lastModified() }
    }

    /** The most recently modified session with turns besides side questions, and the chat's model. */
    private fun readLatestChat(wantTurns: Boolean): Pair<ChatSnapshot?, String?> {
        val files = sessionDirs().flatMap { listSessionFiles(it) }.sortedByDescending { it.lastModified() }
        if (files.isEmpty()) {
            log.info("btw: no local chat sessions found")
            return null to null
        }
        var fallbackModel: String? = null
        for (f in files.take(SESSION_CANDIDATES)) {
            if (f.length() > MAX_SESSION_BYTES) {
                log.info("btw: chat session ${f.name} is too large, skipped")
                continue
            }
            val text = try {
                f.readText()
            } catch (_: Exception) {
                continue
            }
            val info = ChatSessionParse.parseInfo(text, SKIP)
            if (fallbackModel == null) fallbackModel = info?.modelId
            if (!ChatSessionParse.isWorkingSession(info)) continue
            val snap = info!!.snapshot!!
            log.info("btw: chat session ${f.name}: ${snap.turns.size} of ${snap.totalTurns} turns" + (info.modelId?.let { ", model $it" } ?: "") + if (wantTurns) "" else " (content not used)")
            val age = maxOf(0.0, (System.currentTimeMillis() - f.lastModified()) / 60000.0)
            return (if (wantTurns) snap.copy(ageMinutes = age) else null) to (info.modelId ?: fallbackModel)
        }
        log.info("btw: no working chat session found")
        return null to fallbackModel
    }

    fun gatherContext(editor: EditorSnapshot?, references: List<String>?): BtwContext {
        val includeChat = ContingentSettings.instance.state.btwIncludeChat
        val ctx = BtwContext(editor = editor, references = references?.takeIf { it.isNotEmpty() })
        try {
            val (chat, model) = readLatestChat(includeChat)
            ctx.chatModelId = model
            if (includeChat) ctx.chat = chat
        } catch (e: Throwable) {
            log.info("btw: chat session unavailable (${e.message})")
        }
        if (!includeChat) log.info("btw: chat content not used (setting \"Include current chat in btw\" is off)")
        return ctx
    }

    // ------------------------------------------------------------------ model

    private sealed class Pick {
        data class Ok(val model: CopilotModel, val source: String) : Pick()
        data class NoIncluded(val families: List<String>) : Pick()
        object NotChosen : Pick()
    }

    fun modelSourceLabel(source: String): String = when (source) {
        "chat" -> L10n.t("same model as your chat")
        "included" -> L10n.t("included model · no premium requests")
        else -> L10n.t("chosen by you")
    }

    private fun pickModel(project: Project?, chatModelId: String?): Pick {
        val models = CopilotApi.models()
        if (models.isEmpty()) {
            throw CopilotApi.ApiException(L10n.t("No Copilot chat models are available. Check that GitHub Copilot is installed and you are signed in."))
        }
        if (ModelMatch.btwModeOf(ContingentSettings.instance.state.btwModel) == "included") {
            val m = ModelMatch.chooseModel(models, ContingentSettings.instance.preferredModels)
            return if (m != null) Pick.Ok(m, "included") else Pick.NoIncluded(ModelMatch.availableFamilies(models))
        }
        ModelMatch.match(models, chatModelId)?.let { return Pick.Ok(it, "chat") }
        log.info(if (chatModelId != null) "btw: chat model $chatModelId is not available here" else "btw: chat model not detected")
        ContingentStore.instance.btwChosenModel?.let { rem ->
            (ModelMatch.match(models, rem.first) ?: ModelMatch.match(models, rem.second))?.let { return Pick.Ok(it, "chosen") }
            log.info("btw: previously chosen model ${rem.second} is not available")
        }
        val picked = chooseModel(project, models) ?: return Pick.NotChosen
        return Pick.Ok(picked, "chosen")
    }

    private val chooseLock = Any()

    /** Asks once which model btw should use and remembers it. Safe to call from any thread. */
    fun chooseModel(project: Project?, available: List<CopilotModel>? = null): CopilotModel? = synchronized(chooseLock) {
        val models = (available ?: CopilotApi.models()).sortedBy { (it.name ?: it.id).lowercase() }
        if (models.isEmpty()) return null
        val current = ContingentStore.instance.btwChosenModel
        val labels = models.map { m ->
            val mult = m.multiplier?.let { if (it == 0.0) " · 0×" else " · ${io.github.m313.contingentwatcher.services.Format.fmt(it)}×" } ?: ""
            (m.name ?: m.id) + mult + if (current?.first == m.id) " (${L10n.t("current choice")})" else ""
        }.toTypedArray()
        var index = -1
        // any(): also works while a modal dialog (e.g. the settings) is open.
        ApplicationManager.getApplication().invokeAndWait({
            val dialog = io.github.m313.contingentwatcher.ui.ChooseDialog(
                project,
                L10n.t("Model for btw side questions"),
                L10n.t("Used when your chat's model can't be detected. Premium models use premium requests."),
                labels.toList(),
                maxOf(0, models.indexOfFirst { it.id == current?.first }),
            )
            if (dialog.showAndGet()) index = dialog.selectedIndex
        }, com.intellij.openapi.application.ModalityState.any())
        if (index < 0) return null
        val m = models[index]
        ContingentStore.instance.btwChosenModel = Triple(m.id, m.family, m.name)
        return m
    }

    private fun noModelMarkdown(families: List<String>): String = listOf(
        L10n.t("None of your preferred included models is available, so I did not answer, to avoid using a premium request by accident."),
        L10n.t("Available model families: {0}", if (families.isEmpty()) "–" else families.joinToString(", ") { "`$it`" }),
        L10n.t("Set your preferred families in the settings (Copilot Contingent › btw › Free models)."),
    ).joinToString("\n\n")

    // ------------------------------------------------------------------ run

    /** Runs on a pooled thread. Never throws. */
    fun run(project: Project?, card: BtwCardHandle, question: String, editor: EditorSnapshot?, references: List<String>? = null) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val ctx = gatherContext(editor, references)
                val contextLabel = BtwPrompt.describeContext(ctx, contextLabels())
                card.update { context = contextLabel }
                if (card.cancel.isCancelled) {
                    card.finish("cancelled")
                    return@executeOnPooledThread
                }
                val pick = pickModel(project, ctx.chatModelId)
                val (model, source) = when (pick) {
                    is Pick.Ok -> pick.model to pick.source
                    is Pick.NoIncluded -> {
                        log.info("btw: no preferred included model available (have: ${pick.families.joinToString(", ").ifEmpty { "–" }})")
                        card.update { fix = "openSettings" }
                        card.setAnswer(noModelMarkdown(pick.families))
                        card.finish("noModel")
                        return@executeOnPooledThread
                    }
                    Pick.NotChosen -> {
                        card.finish("error", L10n.t("No model chosen, so btw did not answer. Choose a model and try again."))
                        return@executeOnPooledThread
                    }
                }
                if (card.cancel.isCancelled) {
                    card.finish("cancelled")
                    return@executeOnPooledThread
                }
                card.update {
                    this.model = model.name ?: model.id
                    modelNote = modelSourceLabel(source)
                    status = "streaming"
                }
                log.info("btw: asking ${model.name ?: model.id} (${model.family}, $source) · context: $contextLabel")
                CopilotApi.stream(
                    model,
                    listOf(BtwPrompt.buildInstructionMessage(ctx), BtwPrompt.buildQuestionMessage(question)),
                    onText = { card.append(it) },
                    isCancelled = { card.cancel.isCancelled },
                    register = { abort -> card.cancel.onCancel(abort) },
                )
                card.finish(if (card.cancel.isCancelled) "cancelled" else "done")
            } catch (e: Throwable) {
                if (card.cancel.isCancelled) {
                    card.finish("cancelled")
                } else {
                    val message = e.message ?: e.javaClass.simpleName
                    log.warn("btw: $message")
                    card.finish("error", message)
                }
            }
        }
    }
}
