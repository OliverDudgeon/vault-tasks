package com.vaulttasks

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters

/** A query line this app can't follow. Shown rather than ignored, so a widget never silently shows too much. */
class QueryError(line: String) : Exception("Can't use the query line \"$line\"")

class Query(val filters: List<(Task) -> Boolean>, val sorts: List<Comparator<Task>>, val limit: Int?)

/**
 * A subset of the Obsidian Tasks query language (Q-09): one filter per line, all of which must match, with the
 * plugin's semantics. Filters: `done`, `not done`, `status.type is [not] TODO|IN_PROGRESS|DONE|CANCELLED|NON_TASK`,
 * `is [not] recurring`, `priority is [above|below|not] <level>`, `has|no <field> date`, `<field> [on|before|after|
 * on or before|on or after|in …] <date or range>` for due, scheduled, starts, created, done, cancelled and happens,
 * `description|path|filename|folder|root includes|does not include|regex matches|regex does not match <text>`,
 * `tags include|do not include <text>`, `has tags`, `no tags`, `has id`, `no id`, and `(…) AND|OR|XOR (…)`, `NOT (…)` combinations.
 * Also `sort by <field> [reverse]` and `limit [to] N [tasks]`. Comments and layout lines (hide, show, group by, …)
 * don't change which tasks match, so they're skipped. [path] gives a task's vault path.
 */
fun parseQuery(text: String, today: LocalDate, path: (Task) -> String): Query {
    val filters = mutableListOf<(Task) -> Boolean>()
    val sorts = mutableListOf<Comparator<Task>>()
    var limit: Int? = null
    for (line in text.lines().map { it.trim() }) {
        val sort = SORT.matchEntire(line)
        val lineLimit = LIMIT.matchEntire(line)
        when {
            line.isEmpty() || line.startsWith("#") || LAYOUT.containsMatchIn(line) -> {}
            sort != null -> sorts += sorter(sort.groupValues[1].lowercase(), path)
                .let { if (sort.groupValues[2].isEmpty()) it else it.reversed() }
            lineLimit != null -> limit = lineLimit.groupValues[1].toInt()
            else -> filters += expression(line, today, path) ?: throw QueryError(line)
        }
    }
    return Query(filters, sorts, limit)
}

private val SORT = Regex("""sort by (\S+)( reverse)?""", RegexOption.IGNORE_CASE)
private val LIMIT = Regex("""limit (?:to )?(\d+)(?: tasks?)?""", RegexOption.IGNORE_CASE)
private val LAYOUT = Regex("""^(?:(?:hide|show|group by|limit groups) |(?:short|full) mode$|explain$|ignore global query$)""", RegexOption.IGNORE_CASE)

/**
 * A filter line, or a boolean combination: `(a) OR (b)`, `NOT (a)`, nested in brackets. As in the plugin, NOT
 * binds tightest, then XOR, then AND, then OR. Null if any part can't be read. ponytail: only () delimiters, not the plugin's [] {} "".
 */
private fun expression(text: String, today: LocalDate, path: (Task) -> String): ((Task) -> Boolean)? {
    val s = text.trim()
    if (!s.startsWith("(") && !s.startsWith("NOT ")) return leaf(s, today, path)
    for (op in listOf("OR", "AND", "XOR")) {
        val parts = splitTopLevel(s, " $op ") ?: return null
        if (parts.size == 1) continue
        val each = parts.map { expression(it, today, path) ?: return null }
        return when (op) {
            "OR" -> { t -> each.any { it(t) } }
            "AND" -> { t -> each.all { it(t) } }
            else -> { t -> each.count { it(t) } % 2 == 1 }
        }
    }
    if (s.startsWith("NOT ")) return expression(s.removePrefix("NOT "), today, path)?.let { inner -> { t -> !inner(t) } }
    if (!s.endsWith(")")) return null
    return expression(s.substring(1, s.length - 1), today, path)
}

/** [s] split at [op] outside brackets; null if the brackets don't balance. */
private fun splitTopLevel(s: String, op: String): List<String>? {
    val parts = mutableListOf<String>()
    var depth = 0
    var from = 0
    for (i in s.indices) {
        when (s[i]) {
            '(' -> depth++
            ')' -> if (--depth < 0) return null
        }
        if (depth == 0 && i >= from && s.startsWith(op, i)) {
            parts += s.substring(from, i)
            from = i + op.length
        }
    }
    return if (depth == 0) parts + s.substring(from) else null
}

private val STATUS_TYPE = Regex("""status\.type (is|is not) (\w+)""", RegexOption.IGNORE_CASE)
private val PRIORITY = Regex("""priority(?: is)?(?: (above|below|not))? (lowest|low|none|medium|high|highest)""", RegexOption.IGNORE_CASE)
private val HAS_DATE = Regex("""(has|no) (due|scheduled|start|created|done|cancelled|happens) date""", RegexOption.IGNORE_CASE)
private val DATE = Regex(
    """(due|scheduled|starts|created|done|cancelled|happens) (((?:on|in) or before|before|(?:on|in) or after|after|on|in)? ?(.*))""",
    RegexOption.IGNORE_CASE,
)
private val TEXT = Regex(
    """(description|path|filename|folder|root|tags?) (includes?|does not include|do not include|regex matches|regex does not match) (.*)""",
    RegexOption.IGNORE_CASE,
)

private fun leaf(line: String, today: LocalDate, path: (Task) -> String): ((Task) -> Boolean)? {
    when (line.lowercase()) {
        "done" -> return { it.done }
        "not done" -> return { !it.done }
        "is recurring" -> return { it.recurring }
        "is not recurring" -> return { !it.recurring }
        "has tags" -> return { it.tags.isNotEmpty() }
        "no tags" -> return { it.tags.isEmpty() }
        "has id" -> return { it.id != null }
        "no id" -> return { it.id == null }
    }
    STATUS_TYPE.matchEntire(line)?.let { m ->
        val type = StatusType.entries.find { it.name.equals(m.groupValues[2], ignoreCase = true) } ?: return null
        val negate = m.groupValues[1].lowercase() == "is not"
        return { (it.type == type) != negate }
    }
    PRIORITY.matchEntire(line)?.let { m ->
        val level = Priority.valueOf(m.groupValues[2].uppercase())
        return when (m.groupValues[1].lowercase()) {
            "above" -> { t -> t.priority > level }
            "below" -> { t -> t.priority < level }
            "not" -> { t -> t.priority != level }
            else -> { t -> t.priority == level }
        }
    }
    HAS_DATE.matchEntire(line)?.let { m ->
        val dates = dates(m.groupValues[2].lowercase())
        val has = m.groupValues[1].lowercase() == "has"
        return { t -> dates(t).any { it != null } == has }
    }
    TEXT.matchEntire(line)?.let { m -> return textFilter(m.groupValues[1].lowercase(), m.groupValues[2].lowercase(), m.groupValues[3], path) }
    DATE.matchEntire(line)?.let { m ->
        val field = m.groupValues[1].lowercase()
        val keyword = m.groupValues[3].lowercase()
        // As in the plugin: if the text after the keyword isn't a date, try it with the keyword ("in 3 days").
        val range = dateRange(m.groupValues[4], today) ?: dateRange(m.groupValues[2], today) ?: return null
        val test: (LocalDate) -> Boolean = when (keyword) {
            "before" -> { d -> d < range.start }
            "after" -> { d -> d > range.endInclusive }
            "on or before", "in or before" -> { d -> d <= range.endInclusive }
            "on or after", "in or after" -> { d -> d >= range.start }
            else -> { d -> d in range }
        }
        val dates = dates(field)
        // Tasks with no start date match every `starts` filter, so they aren't hidden before they could start.
        val missing = field == "starts"
        return { t -> dates(t).filterNotNull().let { if (it.isEmpty()) missing else it.any(test) } }
    }
    return null
}

private fun dates(field: String): (Task) -> List<LocalDate?> = when (field) {
    "due" -> { t -> listOf(t.due) }
    "scheduled" -> { t -> listOf(t.scheduled) }
    "start", "starts" -> { t -> listOf(t.start) }
    "created" -> { t -> listOf(t.created) }
    "done" -> { t -> listOf(t.doneDate) }
    "cancelled" -> { t -> listOf(t.cancelledDate) }
    else -> { t -> listOf(t.start, t.scheduled, t.due) } // happens
}

private fun textFilter(field: String, operator: String, value: String, path: (Task) -> String): ((Task) -> Boolean)? {
    val texts: (Task) -> List<String> = when (field) {
        "description" -> { t -> listOf(t.description) }
        "path" -> { t -> listOf(path(t)) }
        "filename" -> { t -> listOf(path(t).substringAfterLast("/")) }
        "folder" -> { t -> listOf(path(t).substringBeforeLast("/", "") + "/") }
        "root" -> { t -> listOf(path(t).substringBefore("/", "") + "/") }
        else -> { t -> t.tags }
    }
    val negate = "not" in operator
    val matches: (String) -> Boolean = if (operator.startsWith("regex")) {
        // The plugin takes JavaScript /pattern/flags; Java's syntax agrees for everyday patterns.
        val (pattern, flags) = Regex("""/(.*)/([a-z]*)""").matchEntire(value.trim())?.destructured ?: return null
        val regex = runCatching { Regex(pattern, if ('i' in flags) setOf(RegexOption.IGNORE_CASE) else emptySet()) }.getOrNull() ?: return null
        { regex.containsMatchIn(it) }
    } else {
        { it.contains(value.trim(), ignoreCase = true) }
    }
    return { t -> texts(t).any(matches) != negate }
}

private fun sorter(field: String, path: (Task) -> String): Comparator<Task> {
    fun byDate(date: (Task) -> LocalDate?) = compareBy(nullsLast(), date)
    return when (field) {
        "due" -> byDate { it.due }
        "scheduled" -> byDate { it.scheduled }
        "start" -> byDate { it.start }
        "created" -> byDate { it.created }
        "done" -> byDate { it.doneDate }
        "cancelled" -> byDate { it.cancelledDate }
        "happens" -> byDate { listOfNotNull(it.start, it.scheduled, it.due).minOrNull() }
        "priority" -> compareByDescending { it.priority }
        "path" -> compareBy(path)
        "filename" -> compareBy { path(it).substringAfterLast("/") }
        "description" -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.description }
        "status" -> compareBy { it.done }
        // The plugin's order: in progress, to-do, done, cancelled, non-task.
        "status.type" -> compareBy { listOf(StatusType.IN_PROGRESS, StatusType.TODO, StatusType.DONE, StatusType.CANCELLED, StatusType.NON_TASK).indexOf(it.type) }
        else -> throw QueryError("sort by $field")
    }
}

/**
 * A date or range in the plugin's forms: today, tomorrow, yesterday, YYYY-MM-DD, two such dates, in N days,
 * N weeks ago, last|this|next week|month|quarter|year (ISO weeks), YYYY, YYYY-MM, YYYY-Www, YYYY-Qn.
 * ponytail: the plugin also accepts chrono's natural language ("next friday", "3 March"); add forms as needed.
 */
private fun dateRange(input: String, today: LocalDate): ClosedRange<LocalDate>? {
    val s = input.trim().lowercase().replace(Regex(" +"), " ")
    Regex("""(\d{4}-\d{2}-\d{2}) (\d{4}-\d{2}-\d{2})""").matchEntire(s)?.let { m ->
        val (a, b) = m.groupValues.drop(1).map { runCatching { LocalDate.parse(it) }.getOrNull() ?: return null }
        return minOf(a, b)..maxOf(a, b)
    }
    Regex("""(last|this|next) (week|month|quarter|year)""").matchEntire(s)?.let { m ->
        val (which, unit) = m.destructured
        val months = mapOf("week" to 0L, "month" to 1L, "quarter" to 3L, "year" to 12L).getValue(unit)
        val step = mapOf("last" to -1L, "this" to 0L, "next" to 1L).getValue(which)
        val day = if (unit == "week") today.plusWeeks(step) else today.plusMonths(step * months)
        return when (unit) {
            "week" -> day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).let { it..it.plusDays(6) }
            "month" -> day.withDayOfMonth(1)..day.with(TemporalAdjusters.lastDayOfMonth())
            "quarter" -> day.withDayOfMonth(1).withMonth((day.monthValue - 1) / 3 * 3 + 1).let { it..it.plusMonths(3).minusDays(1) }
            else -> day.withDayOfYear(1)..day.with(TemporalAdjusters.lastDayOfYear())
        }
    }
    Regex("""(\d{4})(?:-(\d{2})|-w(\d{2})|-q([1-4]))?""").matchEntire(s)?.let { m ->
        val (y, month, week, quarter) = m.destructured
        val year = LocalDate.of(y.toInt(), 1, 1)
        return runCatching {
            when {
                month.isNotEmpty() -> year.withMonth(month.toInt()).let { it..it.with(TemporalAdjusters.lastDayOfMonth()) }
                week.isNotEmpty() -> year.withDayOfMonth(4).with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, week.toLong())
                    .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).let { it..it.plusDays(6) }
                quarter.isNotEmpty() -> year.withMonth((quarter.toInt() - 1) * 3 + 1).let { it..it.plusMonths(3).minusDays(1) }
                else -> year..year.with(TemporalAdjusters.lastDayOfYear())
            }
        }.getOrNull()
    }
    val date = when (s) {
        "today" -> today
        "tomorrow" -> today.plusDays(1)
        "yesterday" -> today.minusDays(1)
        else -> Regex("""(?:in (\d+) (day|week|month|year)s?|(\d+) (day|week|month|year)s? ago)""").matchEntire(s)?.let { m ->
            val (ahead, aheadUnit, ago, agoUnit) = m.destructured
            val n = if (ahead.isNotEmpty()) ahead.toLong() else -ago.toLong()
            when (aheadUnit.ifEmpty { agoUnit }) {
                "day" -> today.plusDays(n)
                "week" -> today.plusWeeks(n)
                "month" -> today.plusMonths(n)
                else -> today.plusYears(n)
            }
        } ?: runCatching { LocalDate.parse(s) }.getOrNull()
    }
    return date?.let { it..it }
}
