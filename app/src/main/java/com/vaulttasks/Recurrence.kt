package com.vaulttasks

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

data class Occurrence(val start: LocalDate?, val scheduled: LocalDate?, val due: LocalDate?)

/**
 * The dates of the occurrence after [current] under the 🔁 [rule], as the Obsidian Tasks plugin's `Recurrence`
 * computes them. The next date comes after the reference date (due, else scheduled, else start), or after [today]
 * for `when done`. The other dates keep their distance from it. Monthly and yearly rules land on the month's
 * last day when the date doesn't exist (31 January, every month → 28 February), as the plugin does.
 *
 * Null if the rule is outside the supported subset: `every [N] day|week|month|year[s]`, `every weekday`,
 * `every [N] week[s] on <days>`, `every <days>`, `every [N] month[s] on the <Nth>|last`, each optionally
 * `when done`. The caller then mustn't complete the task, or the next occurrence would be lost.
 */
fun nextOccurrence(rule: String, current: Occurrence, today: LocalDate): Occurrence? {
    val text = rule.trim().lowercase().replace(Regex(" +"), " ")
    val whenDone = text.endsWith(" when done")
    val step = stepper(text.removeSuffix(" when done")) ?: return null
    // With no dates, the plugin's next occurrence has none either.
    val reference = current.due ?: current.scheduled ?: current.start ?: return current
    val next = step(if (whenDone) today else reference) ?: return null
    fun shift(date: LocalDate?) = date?.let { next.plusDays(ChronoUnit.DAYS.between(reference, it)) }
    return Occurrence(shift(current.start), shift(current.scheduled), shift(current.due))
}

private val RULE = Regex("""every(?: (\d+))? (day|week|month|year)s?(?: on (.+))?""")
private val MONTH_DAY = Regex("""the (?:(\d{1,2})(?:st|nd|rd|th)|(last)(?: day)?)""")

/** Maps a date to the next one strictly after it, the rule's series starting on that date (rrule's dtstart). */
private fun stepper(rule: String): ((LocalDate) -> LocalDate?)? {
    if (rule == "every weekday") return weekly(1, DayOfWeek.entries.take(5).toSet())
    val match = RULE.matchEntire(rule) ?: return rule.removePrefix("every ").takeIf { it != rule }?.let(::weekdays)?.let { weekly(1, it) }
    val (count, unit, on) = match.destructured
    val n = count.ifEmpty { "1" }.toLong().takeIf { it in 1..999 } ?: return null
    return when {
        on.isEmpty() -> when (unit) {
            "day" -> { date -> date.plusDays(n) }
            "week" -> { date -> date.plusWeeks(n) }
            // plusMonths and plusYears clamp to the month's last day, like the plugin's skip-too-far correction.
            "month" -> { date -> date.plusMonths(n) }
            else -> { date -> date.plusYears(n) }
        }
        unit == "week" -> weekdays(on)?.let { weekly(n, it) }
        unit == "month" -> MONTH_DAY.matchEntire(on)?.let { m -> monthly(n, m.groupValues[1].toIntOrNull()?.takeIf { it in 1..31 } ?: -1) }
        else -> null
    }
}

/** `monday, wednesday and fri` → the days; null if any word isn't a day. */
private fun weekdays(list: String): Set<DayOfWeek>? {
    val words = list.split(Regex(",|\\band\\b| ")).filter { it.isNotBlank() }
    val days = words.map { w -> DayOfWeek.entries.find { w.length >= 2 && it.name.lowercase().startsWith(w) } ?: return null }
    return days.toSet().ifEmpty { null }
}

// rrule's weekly series: weeks start on Monday, and only every nth week from the start's week counts.
private fun weekly(n: Long, days: Set<DayOfWeek>): (LocalDate) -> LocalDate = { from ->
    val monday = from.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    generateSequence(from.plusDays(1)) { it.plusDays(1) }.first { date ->
        date.dayOfWeek in days && ChronoUnit.WEEKS.between(monday, date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))) % n == 0L
    }
}

// rrule's monthly series on a fixed day skips months without that day (the plugin doesn't correct `on` rules); -1 is the last day.
private fun monthly(n: Long, day: Int): (LocalDate) -> LocalDate? = { from ->
    // A bounded search: some rules never land (every 12 months on the 30th, from February).
    generateSequence(from.withDayOfMonth(1)) { it.plusMonths(n) }.take(400)
        .mapNotNull { month -> (if (day == -1) month.lengthOfMonth() else day).takeIf { it <= month.lengthOfMonth() }?.let(month::withDayOfMonth) }
        .firstOrNull { it > from }
}
