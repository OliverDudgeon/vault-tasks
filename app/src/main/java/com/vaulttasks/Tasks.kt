package com.vaulttasks

import java.io.Serializable
import java.time.LocalDate

/** Obsidian Tasks priorities, lowest first: an unmarked task ranks between low and medium, as in the plugin. */
enum class Priority(val emoji: String) { LOWEST("⏬"), LOW("🔽"), NONE(""), MEDIUM("🔼"), HIGH("⏫"), HIGHEST("🔺") }

/**
 * A task line as found in a note. [file] is opaque to this file: a vault path or a SAF document URI.
 * [noteHash] fingerprints the whole note at scan time, so a writer can tell whether [line] is still exact.
 * The other fields are parsed from [text].
 */
data class Task(val file: String, val line: Int, val text: String, val noteHash: Int = 0) : Serializable {
    val status: Char
    val description: String
    val due: LocalDate?
    val scheduled: LocalDate?
    val priority: Priority
    val recurring: Boolean

    /** Done or cancelled, the plugin's `done` filter. Any other status, including unknown custom ones, is open. */
    val done get() = status in "xX-"

    init {
        val match = TASK.find(text)
        status = match?.groupValues?.get(2)?.single() ?: ' '
        var rest = (match?.groupValues?.get(3) ?: text).replace(BLOCK_ID, "").trim()
        var due: LocalDate? = null
        var scheduled: LocalDate? = null
        var priority = Priority.NONE
        var recurring = false
        var tags = ""
        // Like the plugin's serializer: peel fields off the end in any order; tags among them go back on the description.
        var runs = 0
        while (runs++ < 20) { // The plugin's maxRuns failsafe.
            val field = FIELD.find(rest) ?: break
            val (prio, symbol, date, recur) = field.destructured
            if (prio.isNotEmpty()) priority = Priority.entries.first { it.emoji == prio }
            when (symbol) {
                "📅", "📆", "🗓" -> due = parseDate(date)
                "⏳", "⌛" -> scheduled = parseDate(date)
            }
            if (recur.isNotEmpty()) recurring = true
            if (field.value.trim().startsWith("#")) tags = "${field.value.trim()} $tags"
            rest = rest.substring(0, field.range.first).trim()
        }
        description = "$rest $tags".trim()
        this.due = due
        this.scheduled = scheduled
        this.priority = priority
        // Also a 🔁 the parser couldn't read: completing a recurring task here would drop its next occurrence.
        this.recurring = recurring || "🔁" in text
    }
}

// Same shape as the Obsidian Tasks plugin's task regex: indentation/blockquote, list marker, checkbox, body.
private val TASK = Regex("""^[\s>]*([-*+]|[0-9]+[.)]) +\[(.)] *(.*)""")
private val FENCE = Regex("""^[\s>]*(`{3,}|~{3,})""")
private val BLOCK_ID = Regex(""" \^[A-Za-z0-9-]+$""")
// One metadata field at the end of a task, per the plugin's DefaultTaskSerializer regexes.
private val FIELD = Regex(
    "(?:(🔺|⏫|🔼|🔽|⏬)\uFE0F?" +
        "|(📅|📆|🗓|⏳|⌛|🛫|➕|✅|❌)\uFE0F? *(\\d{4}-\\d{2}-\\d{2})" +
        "|🔁\uFE0F? *([a-zA-Z0-9, !]+)" +
        "|🏁\uFE0F? *[a-zA-Z]+" +
        "|🆔\uFE0F? *[a-zA-Z0-9-_]+" +
        "|⛔\uFE0F? *[a-zA-Z0-9-_]+(?: *, *[a-zA-Z0-9-_]+ *)*" +
        "|(?:^|\\s)#[^ !@#$%^&*(),.?\":{}|<>]+)$",
)

// A malformed date (2026-02-30) is still consumed as a field, as in the plugin, but matches no date filter.
private fun parseDate(text: String) = runCatching { LocalDate.parse(text) }.getOrNull()

fun parseTasks(file: String, content: String): List<Task> = buildList {
    var fence: String? = null
    // ponytail: fences only, no indented code blocks or HTML blocks; add if real vaults need them.
    for ((i, raw) in content.split("\n").withIndex()) {
        val line = raw.removeSuffix("\r")
        val marker = FENCE.find(line)?.groupValues?.get(1)
        if (fence == null && marker != null) fence = marker
        else if (fence != null && marker != null && marker[0] == fence[0] && marker.length >= fence.length) fence = null
        else if (fence == null && TASK.containsMatchIn(line)) add(Task(file, i, line, content.hashCode()))
    }
}

sealed interface CompleteResult
data class Completed(val content: String) : CompleteResult
/** The note changed since the scan, so the task can't be located unambiguously. Rescan; never guess. */
data object Conflict : CompleteResult
/** Done, cancelled or recurring: completing it here could drop the next occurrence (§3.2 scope note). */
data object Unsupported : CompleteResult

/**
 * Checks [task] off in [content], the note's current text. Touches no other line. While the note is exactly as
 * scanned, the task is at its scanned line. Otherwise it's found by its exact text, which must then be unique in
 * the note: a cached line number alone can point at a duplicate that shifted into place (N-12).
 */
fun completeTask(content: String, task: Task, today: LocalDate): CompleteResult {
    if (task.done || task.recurring) return Unsupported
    val lines = content.split("\n").toMutableList()
    val i = task.line.takeIf { content.hashCode() == task.noteHash && lines.getOrNull(it)?.removeSuffix("\r") == task.text }
        ?: lines.indices.singleOrNull { lines[it].removeSuffix("\r") == task.text }
        ?: return Conflict
    val cr = if (lines[i].endsWith("\r")) "\r" else ""
    val status = TASK.find(task.text)?.groups?.get(2) ?: return Unsupported
    val checked = task.text.replaceRange(status.range, "x")
    // Obsidian only recognises a block id at the very end of the line, so the done date goes before it
    // (and before any trailing whitespace, which is left as it was).
    val body = checked.trimEnd()
    val end = BLOCK_ID.find(body)?.range?.first ?: body.length
    lines[i] = checked.substring(0, end) + " ✅ $today" + checked.substring(end) + cr
    return Completed(lines.joinToString("\n"))
}

/**
 * Completes [task] in its note. Re-reads just before writing so an edit that lands while we compute
 * isn't overwritten. ponytail: SAF has no compare-and-swap, so a write racing the second read can still
 * clobber; that window is milliseconds, and only a provider-level lock could close it.
 */
fun completeInNote(task: Task, today: LocalDate, read: () -> String, write: (before: String, after: String) -> Unit): CompleteResult {
    val before = read()
    val result = completeTask(before, task, today)
    if (result !is Completed) return result
    if (read() != before) return Conflict
    write(before, result.content)
    return result
}
