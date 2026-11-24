package io.github.m313.contingentwatcher.btw

import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.util.Alarm
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/** One side question and its answer, as rendered by web/btw.js. */
class BtwCard(val id: String, val question: String) {
    @Volatile var answer: String = ""
    /** thinking | streaming | done | cancelled | error | noModel */
    @Volatile var status: String = "thinking"
    @Volatile var context: String? = null
    @Volatile var model: String? = null
    @Volatile var modelNote: String? = null
    @Volatile var error: String? = null
    @Volatile var fix: String? = null

    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("id", id)
        addProperty("question", question)
        addProperty("answer", answer)
        addProperty("status", status)
        context?.let { addProperty("context", it) }
        model?.let { addProperty("model", it) }
        modelNote?.let { addProperty("modelNote", it) }
        error?.let { addProperty("error", it) }
        fix?.let { addProperty("fix", it) }
    }
}

/** Cancellation of one running answer. */
class BtwCancel {
    private val flag = AtomicBoolean(false)
    private val aborts = CopyOnWriteArrayList<() -> Unit>()
    val isCancelled: Boolean get() = flag.get()

    fun cancel() {
        if (flag.compareAndSet(false, true)) aborts.forEach { runCatching { it() } }
    }

    fun onCancel(abort: () -> Unit) {
        aborts.add(abort)
        if (flag.get()) runCatching { abort() }
    }
}

/** Handle for one card while it is being answered. */
interface BtwCardHandle {
    val id: String
    val cancel: BtwCancel
    fun update(patch: BtwCard.() -> Unit)
    fun append(text: String)
    fun setAnswer(markdown: String)
    fun finish(status: String, error: String? = null)
}

/** A btw window (one per project frame) that mirrors the cards. */
interface BtwView {
    fun post(msg: JsonObject)
}

/**
 * Holds the btw cards for this IDE session (nothing is persisted) and pushes
 * changes to every open btw window.
 */
@Service(Service.Level.APP)
class BtwHub {
    private val cards = CopyOnWriteArrayList<BtwCard>()
    private val running = ConcurrentHashMap<String, BtwCancel>()
    private val views = CopyOnWriteArrayList<BtwView>()
    private var seq = 0

    fun attach(view: BtwView) {
        views.add(view)
    }

    fun detach(view: BtwView) {
        views.remove(view)
    }

    fun allCards(): List<BtwCard> = cards.toList()

    private fun broadcast(msg: JsonObject) = views.forEach { runCatching { it.post(msg) } }

    private fun cardMsg(card: BtwCard) = JsonObject().apply {
        addProperty("type", "card")
        add("card", card.toJson())
    }

    @Synchronized
    fun startCard(question: String): BtwCardHandle {
        val card = BtwCard("${java.lang.Long.toString(System.currentTimeMillis(), 36)}-${++seq}", question.trim())
        cards.add(card)
        while (cards.size > MAX_CARDS) cards.removeAt(0)
        val cancel = BtwCancel()
        running[card.id] = cancel
        broadcast(cardMsg(card))

        val pending = StringBuilder()
        val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, ApplicationManager.getApplication())
        fun flush() {
            val text: String
            val at: Int
            synchronized(pending) {
                if (pending.isEmpty()) return
                text = pending.toString()
                at = card.answer.length - text.length
                pending.setLength(0)
            }
            broadcast(JsonObject().apply {
                addProperty("type", "delta")
                addProperty("id", card.id)
                addProperty("text", text)
                addProperty("at", at)
            })
        }
        fun publish() {
            flush()
            if (cards.contains(card)) broadcast(cardMsg(card))
        }

        return object : BtwCardHandle {
            override val id = card.id
            override val cancel = cancel

            override fun update(patch: BtwCard.() -> Unit) {
                card.patch()
                publish()
            }

            override fun append(text: String) {
                if (text.isEmpty()) return
                synchronized(pending) {
                    card.answer += text
                    if (card.status == "thinking") card.status = "streaming"
                    pending.append(text)
                }
                // Throttle: one flush at most every few milliseconds while streaming.
                if (alarm.isEmpty) alarm.addRequest({ flush() }, DELTA_FLUSH_MS)
            }

            override fun setAnswer(markdown: String) {
                synchronized(pending) {
                    pending.setLength(0)
                    card.answer = markdown
                }
                publish()
            }

            override fun finish(status: String, error: String?) {
                com.intellij.openapi.util.Disposer.dispose(alarm)
                card.status = status
                card.error = error
                publish()
                running.remove(card.id, cancel)
            }
        }
    }

    fun cancel(id: String) {
        running[id]?.cancel()
    }

    /** Cancels running answers and removes all cards. */
    fun clear() {
        running.values.forEach { it.cancel() }
        cards.clear()
        broadcast(JsonObject().apply { addProperty("type", "clear") })
    }

    companion object {
        private const val MAX_CARDS = 50
        private const val DELTA_FLUSH_MS = 40

        val instance: BtwHub
            get() = ApplicationManager.getApplication().getService(BtwHub::class.java)
    }
}
