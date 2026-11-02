package com.vaulttasks

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

enum class Status { OPEN, DONE, ANY }
enum class Sort { DUE, PRIORITY }

/** Due between today + [from] and today + [to] days, inclusive, in the local calendar. A null end is unbounded. */
data class Due(val from: Int?, val to: Int?) {
    companion object {
        val OVERDUE = Due(null, -1)
        val TODAY = Due(0, 0)
        val ON_OR_BEFORE_TODAY = Due(null, 0)
        fun next(days: Int, includeOverdue: Boolean = false) = Due(if (includeOverdue) null else 0, days)
    }
}

/**
 * A saved query over the whole vault. [due] and [priority] are combined with AND, or OR if [anyOf];
 * a task with no due date never matches [due]. [status] always applies.
 */
data class Filter(
    val name: String,
    val status: Status = Status.OPEN,
    val due: Due? = null,
    val priority: ClosedRange<Priority>? = null,
    val anyOf: Boolean = false,
    val sort: Sort = Sort.DUE,
    val id: String = UUID.randomUUID().toString(),
) {
    fun select(tasks: List<Task>, today: LocalDate): List<Task> = tasks.filter { matches(it, today) }.sortedWith(order())

    private fun matches(task: Task, today: LocalDate): Boolean {
        val statusOk = when (status) {
            Status.OPEN -> !task.done
            Status.DONE -> task.done
            Status.ANY -> true
        }
        val checks = listOfNotNull(
            due?.let { (from, to) ->
                val days = task.due?.let { ChronoUnit.DAYS.between(today, it) } ?: return@let false
                (from == null || days >= from) && (to == null || days <= to)
            },
            priority?.let { task.priority in it },
        )
        return statusOk && (checks.isEmpty() || if (anyOf) checks.any { it } else checks.all { it })
    }

    private fun order(): Comparator<Task> {
        val byDue = compareBy<Task, LocalDate?>(nullsLast()) { it.due }
        val byPriority = compareByDescending<Task> { it.priority }
        val first = if (sort == Sort.DUE) byDue.then(byPriority) else byPriority.then(byDue)
        return first.thenBy { it.file }.thenBy { it.line }
    }
}

/** The saved form: one filter per line, tab-separated. Append new fields at the end so older saves still read. */
fun encodeFilters(filters: List<Filter>): String = filters.joinToString("\n") { f ->
    listOf(
        f.id, f.name.replace(Regex("\\s"), " "), f.status, f.sort, f.anyOf,
        f.due?.let { "${it.from ?: ""}:${it.to ?: ""}" } ?: "",
        f.priority?.let { "${it.start}:${it.endInclusive}" } ?: "",
    ).joinToString("\t")
}

fun decodeFilters(saved: String): List<Filter> = saved.lines().filter { it.isNotEmpty() }.map { line ->
    val fields = line.split("\t")
    val (id, name, status, sort, anyOf) = fields
    val due = fields.getOrElse(5) { "" }
    val priority = fields.getOrElse(6) { "" }
    Filter(
        name, Status.valueOf(status),
        due.takeIf { it.isNotEmpty() }?.split(":")?.let { (from, to) -> Due(from.toIntOrNull(), to.toIntOrNull()) },
        priority.takeIf { it.isNotEmpty() }?.split(":")?.let { (low, high) -> Priority.valueOf(low)..Priority.valueOf(high) },
        anyOf.toBoolean(), Sort.valueOf(sort), id,
    )
}
