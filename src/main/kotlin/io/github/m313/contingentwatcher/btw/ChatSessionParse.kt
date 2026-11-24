package io.github.m313.contingentwatcher.btw

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive

/**
 * Best-effort parser for locally persisted Copilot chat sessions (JSON or a
 * JSONL mutation log, as written by VS Code's chat). The format is undocumented,
 * so this is deliberately defensive: it never throws, skips anything it does not
 * understand and returns null when nothing usable is left.
 *
 * Supported shapes:
 * - JSON: `{ requests: [{ message: { text }, response: [...] }] }`
 * - JSONL: `{ kind: 0, v: snapshot }`, `{ kind: 1, k: path, v }` (set),
 *   `{ kind: 2, k: path, v: items, i? }` (push / splice), `{ kind: 3, k: path }` (delete).
 *   Lines that are a whole session or a single request object are accepted too.
 */
object ChatSessionParse {
    data class Options(
        /** Keep at most this many of the newest turns. */
        val maxTurns: Int = 8,
        /** Total character budget for all kept turns. */
        val maxChars: Int = 12000,
        /** Upper bound for a single message. */
        val maxCharsPerMessage: Int = 4000,
        /** Requests handled by these chat participants are skipped. */
        val skipAgentIds: List<String> = emptyList(),
        /** Requests whose text starts with one of these prefixes are skipped. */
        val skipPrefixes: List<String> = emptyList(),
    )

    data class Info(
        /** Recent turns, if the session has any usable ones. */
        val snapshot: ChatSnapshot?,
        /** Model selected in the chat, if recorded. */
        val modelId: String?,
    )

    private const val MAX_TOOL_LINE = 200
    private const val MIN_PARTIAL = 400
    private const val MAX_MODEL_REF = 200

    private fun obj(v: JsonElement?): JsonObject? = if (v != null && v.isJsonObject) v.asJsonObject else null
    private fun arr(v: JsonElement?): JsonArray? = if (v != null && v.isJsonArray) v.asJsonArray else null
    private fun str(v: JsonElement?): String? = if (v != null && v.isJsonPrimitive && v.asJsonPrimitive.isString) v.asString else null
    private fun intOf(v: JsonElement?): Int? {
        if (v == null || !v.isJsonPrimitive || !v.asJsonPrimitive.isNumber) return null
        val d = v.asDouble
        return if (d == Math.floor(d) && d >= 0 && d < Int.MAX_VALUE) d.toInt() else null
    }

    /** A plain string or an IMarkdownString-like `{ value }`. */
    private fun mdText(v: JsonElement?): String? = str(v) ?: obj(v)?.let { str(it.get("value")) }

    fun parse(text: String, opts: Options = Options()): ChatSnapshot? = parseInfo(text, opts)?.snapshot

    /** Parses a session file into recent turns and the chat's current model. Never throws. */
    fun parseInfo(text: String, opts: Options = Options()): Info? = try {
        val session = parseSessionObject(text)
        if (session == null) {
            null
        } else {
            val turns = extractTurns(session, opts)
            val info = Info(if (turns.isNotEmpty()) selectRecentTurns(turns, opts) else null, extractSessionModel(session))
            if (info.snapshot != null || info.modelId != null) info else null
        }
    } catch (_: Throwable) {
        null
    }

    private fun modelRef(v: JsonElement?): String? {
        val ref = when {
            v == null -> null
            v.isJsonPrimitive && v.asJsonPrimitive.isString -> v.asString
            v.isJsonObject -> {
                val o = v.asJsonObject
                str(o.get("identifier")) ?: str(o.get("id")) ?: str(o.get("modelId"))
                    ?: obj(o.get("metadata"))?.let { m -> str(m.get("id")) ?: str(m.get("family")) }
            }
            else -> null
        }?.trim()
        return if (!ref.isNullOrEmpty() && ref.length <= MAX_MODEL_REF) ref else null
    }

    /** The model currently selected in the chat, else the model of the newest request that names one. */
    fun extractSessionModel(session: JsonObject): String? {
        val input = obj(session.get("inputState"))
        val fromSession = (input?.let { modelRef(it.get("selectedModel")) ?: modelRef(it.get("model")) ?: modelRef(it.get("modelId")) })
            ?: modelRef(session.get("selectedModel"))
        if (fromSession != null) return fromSession
        val requests = arr(session.get("requests")) ?: return null
        for (i in requests.size() - 1 downTo 0) {
            val r = obj(requests[i]) ?: continue
            val meta = obj(obj(r.get("result"))?.get("metadata"))
            val ref = modelRef(r.get("modelId")) ?: modelRef(r.get("selectedModel")) ?: modelRef(r.get("model"))
                ?: obj(r.get("agent"))?.let { modelRef(it.get("modelId")) }
                ?: meta?.let { modelRef(it.get("modelId")) ?: modelRef(it.get("model")) }
            if (ref != null) return ref
        }
        return null
    }

    private fun parseJson(s: String): JsonElement? = try {
        JsonParser.parseString(s)
    } catch (_: Throwable) {
        null
    }

    /** Returns the session object (something with a `requests` array) or null. */
    fun parseSessionObject(text: String): JsonObject? {
        if (text.isBlank()) return null
        val trimmed = text.removePrefix("﻿").trim()
        // A single JSON document (strict: must be one value).
        val whole = if (trimmed.startsWith("{") || trimmed.startsWith("[")) parseSingle(trimmed) else null
        if (whole != null) {
            val o = obj(whole)
            if (o != null && arr(o.get("requests")) != null) return o
            // A one-line JSONL log parses as a single JSON value too.
            return applyLog(listOf(whole))
        }
        val lines = trimmed.split(Regex("\r?\n")).mapNotNull { l -> l.trim().takeIf { it.isNotEmpty() }?.let { parseJson(it) } }
        return applyLog(lines)
    }

    /** Parses `s` as exactly one JSON value; null for JSONL or invalid input. */
    private fun parseSingle(s: String): JsonElement? = try {
        val reader = com.google.gson.stream.JsonReader(java.io.StringReader(s))
        val el = JsonParser.parseReader(reader)
        if (reader.peek() == com.google.gson.stream.JsonToken.END_DOCUMENT) el else null
    } catch (_: Throwable) {
        null
    }

    private sealed class Key {
        data class S(val v: String) : Key()
        data class I(val v: Int) : Key()
    }

    private val FORBIDDEN_KEYS = setOf("__proto__", "constructor", "prototype")

    private fun validPath(k: JsonElement?): List<Key>? {
        val a = arr(k) ?: return null
        val out = ArrayList<Key>()
        for (e in a) {
            if (e.isJsonPrimitive && e.asJsonPrimitive.isString && e.asString !in FORBIDDEN_KEYS) {
                out.add(Key.S(e.asString))
            } else {
                val i = intOf(e) ?: return null
                out.add(Key.I(i))
            }
        }
        return out
    }

    private fun looksLikeRequest(v: JsonObject): Boolean =
        (v.has("message") || v.has("response")) && !(v.has("kind") && v.has("k"))

    private fun applyLog(entries: List<JsonElement>): JsonObject? {
        var root: JsonElement? = null
        for (e in entries) {
            try {
                root = applyEntry(root, e)
            } catch (_: Throwable) {
                // Skip entries that do not fit the current state.
            }
        }
        val o = obj(root)
        return if (o != null && arr(o.get("requests")) != null) o else null
    }

    private fun applyEntry(root: JsonElement?, el: JsonElement): JsonElement? {
        val e = obj(el) ?: return root
        if (arr(e.get("requests")) != null && !(e.has("kind") && e.has("v"))) return e
        val kind = intOf(e.get("kind"))
        if (kind == 0 && e.has("v")) {
            return obj(e.get("v")) ?: root
        }
        if (kind == 1 && e.has("v")) {
            val path = validPath(e.get("k")) ?: return root
            if (path.isEmpty()) return e.get("v")
            val parent = walk(root, path.dropLast(1), true)
            setKey(parent, path.last(), e.get("v"))
            return root
        }
        if (kind == 2) {
            val path = validPath(e.get("k")) ?: return root
            val items = arr(e.get("v")) ?: return root
            var a = arr(walk(root, path, false))
            if (a == null) {
                if (path.isEmpty()) return root
                val parent = walk(root, path.dropLast(1), true)
                a = JsonArray()
                setKey(parent, path.last(), a)
            }
            val i = intOf(e.get("i"))
            if (i != null && i <= a.size()) {
                while (a.size() > i) a.remove(a.size() - 1)
            }
            for (it in items) a.add(it)
            return root
        }
        if (kind == 3) {
            val path = validPath(e.get("k")) ?: return root
            if (path.isEmpty()) return root
            val parent = walk(root, path.dropLast(1), false)
            val key = path.last()
            if (parent is JsonArray && key is Key.I) {
                if (key.v < parent.size()) parent.remove(key.v)
            } else if (parent is JsonObject && key is Key.S) {
                parent.remove(key.v)
            }
            return root
        }
        // A bare request object appended as a line.
        val r = obj(root)
        if (looksLikeRequest(e) && r != null) {
            arr(r.get("requests"))?.let {
                it.add(e)
                return root
            }
        }
        return root
    }

    /** Walks `path` from `root`; with `create`, missing containers are created. Throws if impossible. */
    private fun walk(root: JsonElement?, path: List<Key>, create: Boolean): JsonElement? {
        var cur: JsonElement? = root
        for ((idx, key) in path.withIndex()) {
            var next: JsonElement? = when {
                cur is JsonArray && key is Key.I -> if (key.v < cur.size()) cur[key.v] else null
                cur is JsonObject && key is Key.S -> cur.get(key.v)
                else -> throw IllegalStateException("bad path")
            }
            if (next == null || next is JsonNull) {
                if (!create) return null
                next = if (path.getOrNull(idx + 1) is Key.I) JsonArray() else JsonObject()
                setKey(cur, key, next)
            }
            cur = next
        }
        return cur
    }

    private fun setKey(container: JsonElement?, key: Key, value: JsonElement?) {
        val v = value ?: JsonNull.INSTANCE
        if (container is JsonArray && key is Key.I) {
            when {
                key.v > container.size() -> throw IllegalStateException("sparse")
                key.v == container.size() -> container.add(v)
                else -> container.set(key.v, v)
            }
        } else if (container is JsonObject && key is Key.S) {
            container.add(key.v, v)
        } else {
            throw IllegalStateException("bad container")
        }
    }

    fun extractTurns(session: JsonObject, opts: Options = Options()): List<ChatTurn> {
        val requests = arr(session.get("requests")) ?: return emptyList()
        val skipAgents = opts.skipAgentIds.toSet()
        val skipPrefixes = opts.skipPrefixes.map { it.lowercase() }
        val out = ArrayList<ChatTurn>()
        for (el in requests) {
            val r = obj(el) ?: continue
            val agentId = obj(r.get("agent"))?.let { str(it.get("id")) }
            if (agentId != null && agentId in skipAgents) continue
            val user = requestText(r).trim()
            if (user.isNotEmpty() && skipPrefixes.any { user.lowercase().startsWith(it) }) continue
            val assistant = responseText(r.get("response")).trim()
            if (user.isEmpty() && assistant.isEmpty()) continue
            out.add(ChatTurn(user, assistant))
        }
        return out
    }

    fun requestText(r: JsonObject): String {
        val m = r.get("message")
        str(m)?.let { return it }
        obj(m)?.let { mo ->
            val t = str(mo.get("text"))
            if (!t.isNullOrEmpty()) return t
            arr(mo.get("parts"))?.let { parts -> return parts.joinToString("") { p -> obj(p)?.let { str(it.get("text")) } ?: "" } }
        }
        return str(r.get("prompt")) ?: ""
    }

    fun responseText(resp: JsonElement?): String {
        var parts: List<JsonElement> = emptyList()
        when {
            resp == null -> {}
            resp.isJsonArray -> parts = resp.asJsonArray.toList()
            resp.isJsonObject -> {
                val o = resp.asJsonObject
                when {
                    arr(o.get("value")) != null -> parts = o.getAsJsonArray("value").toList()
                    arr(o.get("parts")) != null -> parts = o.getAsJsonArray("parts").toList()
                    str(o.get("value")) != null -> return o.get("value").asString
                }
            }
            str(resp) != null -> return resp.asString
        }
        val text = StringBuilder()
        fun push(s: String?, block: Boolean = false) {
            if (s.isNullOrEmpty()) return
            if (block && text.isNotEmpty() && !text.endsWith("\n")) text.append('\n')
            text.append(s)
            if (block) text.append('\n')
        }
        for (p in parts) {
            val plain = str(p)
            if (plain != null) {
                push(plain)
                continue
            }
            val o = obj(p) ?: continue
            val kind = str(o.get("kind"))
            if (kind.isNullOrEmpty() || kind == "markdownContent" || kind == "markdownVuln") {
                push(if (!kind.isNullOrEmpty()) mdText(o.get("content")) else str(o.get("value")))
                continue
            }
            when (kind) {
                "inlineReference" -> (str(o.get("name")) ?: refName(o.get("inlineReference")))?.let { push("`$it`") }
                "toolInvocation", "toolInvocationSerialized" ->
                    (mdText(o.get("pastTenseMessage")) ?: mdText(o.get("invocationMessage")))?.let { push("[tool] " + oneLine(it, MAX_TOOL_LINE), true) }
                "textEditGroup" -> refName(o.get("uri"))?.let { push("[edited $it]", true) }
                else -> {} // thinking, warnings, progress, confirmations…: skip.
            }
        }
        return text.toString()
    }

    private fun refName(ref: JsonElement?): String? {
        val r = obj(ref) ?: return null
        val uri = obj(r.get("uri")) ?: r
        val p = str(uri.get("path")) ?: str(uri.get("fsPath")) ?: str(uri.get("external")) ?: return null
        return p.replace(Regex("[\\\\/]+$"), "").split(Regex("[\\\\/]")).lastOrNull()?.takeIf { it.isNotEmpty() }
    }

    private fun oneLine(s: String, max: Int): String {
        val t = s.replace(Regex("\\s+"), " ").trim()
        return if (t.length <= max) t else t.take(max - 1) + "…"
    }

    /** Shortens `text` to at most ~`max` characters, keeping its head and tail. */
    fun clipMiddle(text: String, max: Int): String {
        if (text.length <= max) return text
        if (max <= 0) return ""
        if (max <= 40) return text.take(maxOf(0, max - 1)) + "…"
        val marker = "\n… [shortened] …\n"
        val keep = max - marker.length
        val head = Math.ceil(keep * 0.6).toInt()
        val tail = keep - head
        return text.take(head) + marker + (if (tail > 0) text.takeLast(tail) else "")
    }

    /** Keeps the newest turns within the turn and character budgets. */
    fun selectRecentTurns(turns: List<ChatTurn>, opts: Options = Options()): ChatSnapshot {
        val maxTurns = maxOf(1, opts.maxTurns)
        val maxChars = maxOf(MIN_PARTIAL, opts.maxChars)
        val perMsg = maxOf(100, opts.maxCharsPerMessage)
        var truncated = turns.size > maxTurns
        val recent = turns.takeLast(maxTurns)
        val kept = ArrayList<ChatTurn>()
        var remaining = maxChars
        for (i in recent.indices.reversed()) {
            var user = clipMiddle(recent[i].user, perMsg)
            var assistant = clipMiddle(recent[i].assistant, perMsg)
            if (user != recent[i].user || assistant != recent[i].assistant) truncated = true
            val cost = user.length + assistant.length
            if (cost <= remaining) {
                kept.add(0, ChatTurn(user, assistant))
                remaining -= cost
                continue
            }
            truncated = true
            if (remaining >= MIN_PARTIAL) {
                val userBudget = minOf(user.length, Math.floor(remaining * 0.4).toInt())
                user = clipMiddle(user, userBudget)
                assistant = clipMiddle(assistant, maxOf(0, remaining - user.length))
                kept.add(0, ChatTurn(user, assistant))
            }
            break
        }
        return ChatSnapshot(kept, turns.size, truncated)
    }

    /** True if the session holds actual work (at least one usable turn besides side questions). */
    fun isWorkingSession(info: Info?): Boolean = info?.snapshot != null && info.snapshot.turns.isNotEmpty()
}

