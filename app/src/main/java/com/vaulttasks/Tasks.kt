package com.vaulttasks

import java.time.LocalDate

/** A task line as found in a note. [file] is opaque to this file: a vault path or a SAF document URI. */
data class Task(val file: String, val line: Int, val text: String) {
    val isOpen get() = TASK.find(text)?.groupValues?.get(2) == " "
}

// Same shape as the Obsidian Tasks plugin's task regex: indentation/blockquote, list marker, checkbox.
private val TASK = Regex("""^[\s>]*([-*+]|[0-9]+[.)]) +\[(.)]""")
private val FENCE = Regex("""^[\s>]*(`{3,}|~{3,})""")
private val BLOCK_ID = Regex(""" \^[A-Za-z0-9-]+$""")

fun parseTasks(file: String, content: String): List<Task> = buildList {
    var fence: String? = null
    // ponytail: fences only, no indented code blocks or HTML blocks; add if real vaults need them.
    for ((i, raw) in content.split("\n").withIndex()) {
        val line = raw.removeSuffix("\r")
        val marker = FENCE.find(line)?.groupValues?.get(1)
        if (fence == null && marker != null) fence = marker
        else if (fence != null && marker != null && marker[0] == fence[0] && marker.length >= fence.length) fence = null
        else if (fence == null && TASK.containsMatchIn(line)) add(Task(file, i, line))
    }
}

sealed interface CompleteResult
data class Completed(val content: String) : CompleteResult
/** The note changed since the scan, so the task can't be located unambiguously. Rescan; never guess. */
data object Conflict : CompleteResult
/** Not an open task, or recurring: completing it here could drop the next occurrence (§3.2 scope note). */
data object Unsupported : CompleteResult

/**
 * Checks [task] off in [content], the note's current text. Touches no other line. The task is found by
 * its exact text, which must be unique in the note: a cached line number alone can point at a duplicate
 * that shifted into place (N-12). ponytail: so duplicate tasks can't be completed here; disambiguate by
 * surrounding lines if that matters.
 */
fun completeTask(content: String, task: Task, today: LocalDate): CompleteResult {
    val status = TASK.find(task.text)?.groups?.get(2)
    if (status?.value != " " || "🔁" in task.text) return Unsupported
    val lines = content.split("\n").toMutableList()
    val i = lines.indices.singleOrNull { lines[it].removeSuffix("\r") == task.text } ?: return Conflict
    val cr = if (lines[i].endsWith("\r")) "\r" else ""
    val checked = task.text.replaceRange(status.range, "x").trimEnd()
    // Obsidian only recognises a block id at the very end of the line, so the done date goes before it.
    val end = BLOCK_ID.find(checked)?.range?.first ?: checked.length
    lines[i] = checked.substring(0, end) + " ✅ $today" + checked.substring(end) + cr
    return Completed(lines.joinToString("\n"))
}

/**
 * Completes [task] in its note. Re-reads just before writing so an edit that lands while we compute
 * isn't overwritten. ponytail: SAF has no compare-and-swap, so a write racing the second read can still
 * clobber; that window is milliseconds, and only a provider-level lock could close it.
 */
fun completeInNote(task: Task, today: LocalDate, read: () -> String, write: (String) -> Unit): CompleteResult {
    val before = read()
    val result = completeTask(before, task, today)
    if (result !is Completed) return result
    if (read() != before) return Conflict
    write(result.content)
    return result
}
