package com.vaulttasks

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** How a widget groups its tasks. AGENDA shows only dated tasks, under the day they're due. */
enum class GroupBy { NONE, NOTE, FOLDER, AGENDA }

/** A heading and its tasks. [title] is null for the single group of [GroupBy.NONE]; [date] is the agenda day; [notePath] is the vault path when grouped by note. */
data class Group(val title: String?, val detail: String?, val tasks: List<Task>, val date: LocalDate? = null, val notePath: String? = null)

/**
 * [tasks], already in the filter's order, in groups. Notes and folders are sorted by path, the vault's top level
 * first; agenda days run earliest first. Tasks keep the filter's order within a group. [path] gives a vault path.
 */
fun groupTasks(tasks: List<Task>, by: GroupBy, today: LocalDate, path: (Task) -> String, locale: Locale = Locale.getDefault()): List<Group> {
    fun folder(t: Task) = path(t).substringBeforeLast("/", "")
    return when (by) {
        GroupBy.NONE -> listOf(Group(null, null, tasks))
        GroupBy.NOTE -> tasks.groupBy(path).toSortedMap(String.CASE_INSENSITIVE_ORDER).map { (note, list) ->
            Group(note.substringAfterLast("/").removeSuffix(".md"), note.substringBeforeLast("/", "").ifEmpty { null }, list, notePath = note)
        }
        GroupBy.FOLDER -> tasks.groupBy(::folder).toSortedMap(String.CASE_INSENSITIVE_ORDER).map { (folder, list) ->
            Group(folder.ifEmpty { "Vault root" }, null, list)
        }
        GroupBy.AGENDA -> tasks.filter { it.due != null }.groupBy { it.due!! }.toSortedMap().map { (day, list) ->
            val days = ChronoUnit.DAYS.between(today, day)
            val relative = mapOf(-1L to "Yesterday", 0L to "Today", 1L to "Tomorrow")[days]
            val weekday = DateTimeFormatter.ofPattern("EEE", locale).format(day)
            val date = DateTimeFormatter.ofPattern(if (day.year == today.year) "d MMM" else "d MMM yyyy", locale).format(day)
            Group(relative ?: weekday, if (relative != null) "$weekday $date" else date, list, day)
        }
    }
}
