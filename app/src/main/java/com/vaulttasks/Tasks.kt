package com.vaulttasks

import java.io.Serializable
import java.time.LocalDate

/** Obsidian Tasks priorities, lowest first: an unmarked task ranks between low and medium, as in the plugin. */
enum class Priority(val emoji: String) { LOWEST("⏬"), LOW("🔽"), NONE(""), MEDIUM("🔼"), HIGH("⏫"), HIGHEST("🔺") }

/** The plugin's status types. [Task.done] is DONE, CANCELLED or NON_TASK, as in its `done` filter. */
enum class StatusType { TODO, IN_PROGRESS, DONE, CANCELLED, NON_TASK }

/**
 * A task line as found in a note. [file] is opaque to this file: a vault path or a SAF document URI.
 * [noteHash] fingerprints the whole note at scan time, so a writer can tell whether [line] is still exact.
 * [heading] is the nearest Markdown heading above the task, for jumping close to it in Obsidian.
 * The other fields are parsed from [text].
 */
data class Task(
    val file: String,
    val line: Int,
    val text: String,
    val noteHash: Int = 0,
    val heading: String? = null,
) : Serializable {
    val status: Char
    val description: String
    val due: LocalDate?
    val scheduled: LocalDate?
    val start: LocalDate?
    val created: LocalDate?
    val doneDate: LocalDate?
    val cancelledDate: LocalDate?
    val priority: Priority
    /** The 🔁 rule text, such as `every week when done`; null if there is none or it can't be read. */
    val recurrence: String?
    val recurring: Boolean
    /** The 🆔 value, for other tasks' ⛔ to point at; null if there is none. */
    val id: String?
    /** The Obsidian block id (`^id`) at the end of the line, without the leading `^`; null if none. */
    val blockId: String?
    val tags: List<String>

    // The plugin's default statuses (todo, done, in progress, cancelled). ponytail: custom statuses from the plugin's
    // settings in .obsidian aren't read, so they're to-do like any unknown symbol; read data.json if a vault needs them.
    val type: StatusType
        get() = when (status) {
            'x', 'X' -> StatusType.DONE
            '/' -> StatusType.IN_PROGRESS
            '-' -> StatusType.CANCELLED
            else -> StatusType.TODO
        }

    val done get() = type == StatusType.DONE || type == StatusType.CANCELLED || type == StatusType.NON_TASK

    init {
        val (body, peeled) = peel(text)
        val fields = peeled.reversed() // Left to right: the leftmost of a repeated field wins, as in the plugin.
        status = TASK.find(text)?.groupValues?.get(2)?.single() ?: ' '
        fun date(vararg symbols: String) = fields.firstOrNull { it.groupValues[2] in symbols }?.let { parseDate(it.groupValues[3]) }
        due = date("📅", "📆", "🗓")
        scheduled = date("⏳", "⌛")
        start = date("🛫")
        created = date("➕")
        doneDate = date("✅")
        cancelledDate = date("❌")
        priority = fields.firstNotNullOfOrNull { f -> Priority.entries.find { it.emoji == f.groupValues[1] && it != Priority.NONE } }
            ?: Priority.NONE
        recurrence = fields.firstOrNull { it.groupValues[4].isNotEmpty() }?.groupValues?.get(4)?.trim()
        id = fields.firstOrNull { it.groupValues[6].isNotEmpty() }?.groupValues?.get(6)
        blockId = BLOCK_ID.find(text.trimEnd())?.value?.trim()?.removePrefix("^")
        // Also a 🔁 the parser couldn't read: completing a recurring task here would drop its next occurrence.
        recurring = recurrence != null || "🔁" in text
        // Tags among the fields go back on the description, as in the plugin.
        val fieldTags = fields.map { it.value.trim() }.filter { it.startsWith("#") }
        description = (listOf(body) + fieldTags).joinToString(" ").trim()
        tags = TAG.findAll(description).map { it.value.trim() }.toList()
    }
}

// Same shape as the Obsidian Tasks plugin's task regex: indentation/blockquote, list marker, checkbox, body.
private val TASK = Regex("""^[\s>]*([-*+]|[0-9]+[.)]) +\[(.)] *(.*)""")
private val FENCE = Regex("""^[\s>]*(`{3,}|~{3,})""")
private val HEADING = Regex("""^#{1,6}[ \t]+(.+?)(?:[ \t]+#+)?[ \t]*$""")
private val BLOCK_ID = Regex(""" \^[A-Za-z0-9-]+$""")
// One metadata field at the end of a task, per the plugin's DefaultTaskSerializer regexes
// (obsidian-tasks, MIT License, Copyright (c) 2021 Clare Macrae, Ilyas Landikov and Martin Schenck).
private val FIELD = Regex(
    "(?:(🔺|⏫|🔼|🔽|⏬)\uFE0F?" +
        "|(📅|📆|🗓|⏳|⌛|🛫|➕|✅|❌)\uFE0F? *(\\d{4}-\\d{2}-\\d{2})" +
        "|🔁\uFE0F? *([a-zA-Z0-9, !]+)" +
        "|🏁\uFE0F? *([a-zA-Z]+)" +
        "|🆔\uFE0F? *([a-zA-Z0-9-_]+)" +
        "|⛔\uFE0F? *[a-zA-Z0-9-_]+(?: *, *[a-zA-Z0-9-_]+ *)*" +
        "|(?:^|\\s)#[^ !@#$%^&*(),.?\":{}|<>]+)$",
)

private val TAG = Regex("""(?:^|\s)#[^ !@#$%^&*(),.?":{}|<>]+""")

/**
 * Splits a task line into its description and its metadata fields, last field first. Like the plugin's serializer,
 * fields are peeled off the end in any order, after any block id. Field ranges are positions in [text].
 */
private fun peel(text: String): Pair<String, List<MatchResult>> {
    val bodyStart = TASK.find(text)?.groups?.get(3)?.range?.first ?: return text to emptyList()
    var end = text.trimEnd().length
    BLOCK_ID.find(text.substring(0, end))?.let { end = it.range.first }
    val fields = mutableListOf<MatchResult>()
    while (fields.size < 20) { // The plugin's maxRuns failsafe.
        end = text.substring(0, end).trimEnd().length.coerceAtLeast(bodyStart)
        val field = FIELD.find(text.substring(0, end))?.takeIf { it.range.first >= bodyStart } ?: break
        fields += field
        end = field.range.first
    }
    return text.substring(bodyStart, end).trim() to fields
}

// A malformed date (2026-02-30) is still consumed as a field, as in the plugin, but matches no date filter.
private fun parseDate(text: String) = runCatching { LocalDate.parse(text) }.getOrNull()

fun parseTasks(file: String, content: String): List<Task> = buildList {
    val lines = content.split("\n")
    var fence: String? = null
    var heading: String? = null
    var inFrontmatter = lines.firstOrNull()?.removeSuffix("\r")?.trimEnd() == "---"
    // ponytail: fences only, no indented code blocks or HTML blocks; add if real vaults need them.
    for ((i, raw) in lines.withIndex()) {
        val line = raw.removeSuffix("\r")
        if (inFrontmatter) {
            if (i > 0 && line.trimEnd().let { it == "---" || it == "..." }) inFrontmatter = false
            continue
        }
        val marker = FENCE.find(line)?.groupValues?.get(1)
        if (fence == null && marker != null) fence = marker
        else if (fence != null && marker != null && marker[0] == fence[0] && marker.length >= fence.length) fence = null
        else if (fence == null) {
            val h = HEADING.find(line)?.groupValues?.get(1)
            if (h != null) {
                heading = inlineMarkdown(h).text.replace('#', ' ').replace(Regex("""\s+"""), " ").trim().ifEmpty { heading }
            } else if (TASK.containsMatchIn(line)) {
                add(Task(file, i, line, content.hashCode(), heading))
            }
        }
    }
}

sealed interface CompleteResult
data class Completed(val content: String) : CompleteResult
/** The note changed since the scan, so the task can't be located unambiguously. Rescan; never guess. */
data object Conflict : CompleteResult
/**
 * Done or cancelled; a custom status, which the vault may define as done; or recurring by a rule [nextOccurrence]
 * can't follow, where completing it could drop the next occurrence.
 */
data object Unsupported : CompleteResult

/**
 * Checks [task] off in [content], the note's current text. Touches no other line. While the note is exactly as
 * scanned, the task is at its scanned line. Otherwise it's found by its exact text, which must then be unique in
 * the note: a cached line number alone can point at a duplicate that shifted into place (N-12).
 * A recurring task gets its next occurrence on the line above, as the plugin does by default; `🏁 delete` removes
 * the completed one.
 */
fun completeTask(content: String, task: Task, today: LocalDate): CompleteResult {
    if (task.status !in " /") return Unsupported
    val next = if (task.recurring) nextOccurrenceLine(task, today) ?: return Unsupported else null
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
    val completed = checked.substring(0, end) + " ✅ $today" + checked.substring(end)
    val delete = peel(task.text).second.any { it.groupValues[5].equals("delete", ignoreCase = true) }
    lines.removeAt(i)
    lines.addAll(i, listOfNotNull(next, completed.takeUnless { delete }).map { it + cr })
    return Completed(lines.joinToString("\n"))
}

/**
 * [task]'s next occurrence: to-do, with its dates moved on, and without the fields the plugin drops from a new
 * occurrence (done, cancelled and created dates, block id, 🆔 and ⛔). Everything else stays as written.
 * Null if the rule isn't supported or a date is malformed, where the plugin would silently drop the recurrence.
 */
private fun nextOccurrenceLine(task: Task, today: LocalDate): String? {
    val rule = task.recurrence ?: return null
    val text = task.text.trimEnd()
    val (_, fields) = peel(text)
    if (fields.any { it.groupValues[3].isNotEmpty() && parseDate(it.groupValues[3]) == null }) return null
    val next = nextOccurrence(rule, Occurrence(task.start, task.scheduled, task.due), today) ?: return null
    val edits = mutableListOf<Pair<IntRange, String>>()
    BLOCK_ID.find(text)?.let { edits += it.range to "" }
    for (field in fields) {
        val date = field.groups[3]
        val new = when (field.groupValues[2]) {
            "📅", "📆", "🗓" -> next.due
            "⏳", "⌛" -> next.scheduled
            "🛫" -> next.start
            else -> null
        }
        val value = field.value.trimStart()
        when {
            date != null && new != null -> edits += date.range to "$new"
            field.groupValues[2] in setOf("✅", "❌", "➕") || value.startsWith("🆔") || value.startsWith("⛔") ->
                edits += (if (text[field.range.first - 1] == ' ') field.range.first - 1..field.range.last else field.range) to ""
        }
    }
    edits += TASK.find(text)!!.groups[2]!!.range to " "
    return edits.sortedByDescending { it.first.first }.fold(text) { line, (range, new) -> line.replaceRange(range, new) }
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
