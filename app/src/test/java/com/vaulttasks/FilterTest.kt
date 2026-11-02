package com.vaulttasks

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class FilterTest {
    private val today = LocalDate.of(2026, 11, 2)

    // The §7 vault.
    private val vault = parseTasks(
        "Projects/Research.md",
        """
            # Research
            - [ ] Review preprint #research 🔺 📅 2026-11-02
            - [ ] Draft notes #research ⏳ 2026-11-03 📅 2026-11-07
        """.trimIndent(),
    ) + parseTasks(
        "Personal/Household/Tasks.md",
        """
            # Household
            - [ ] Order supplies ⏫ 📅 2026-11-06
            - [ ] Clean kitchen 🔽
            - [ ] Weekly review 🔁 every week 📅 2026-11-02
        """.trimIndent(),
    )

    private fun names(filter: Filter, tasks: List<Task> = vault) = filter.select(tasks, today).map { it.description }

    @Test
    fun `AT-02 due today`() {
        assertEquals(listOf("Weekly review", "Review preprint #research").sorted(), names(Filter("Today", due = Due.TODAY)).sorted())
    }

    @Test
    fun `AT-03 due in the next 7 days AND priority high or higher`() {
        val filter = Filter("Soon & high", due = Due.next(7), priority = Priority.HIGH..Priority.HIGHEST)

        assertEquals(listOf("Review preprint #research", "Order supplies"), names(filter))
    }

    @Test
    fun `AT-04 due today OR priority high or higher, without duplicates`() {
        val filter = Filter("Today or high", due = Due.TODAY, priority = Priority.HIGH..Priority.HIGHEST, anyOf = true)

        assertEquals(listOf("Review preprint #research", "Weekly review", "Order supplies"), names(filter))
    }

    @Test
    fun `overdue, on or before today, and due soon optionally including overdue`() {
        val tasks = parseTasks("n.md", "- [ ] Late 📅 2026-10-30\n- [ ] Now 📅 2026-11-02\n- [ ] Week 📅 2026-11-09\n- [ ] Later 📅 2026-11-10\n- [ ] Undated")

        assertEquals(listOf("Late"), names(Filter("", due = Due.OVERDUE), tasks))
        assertEquals(listOf("Late", "Now"), names(Filter("", due = Due.ON_OR_BEFORE_TODAY), tasks))
        assertEquals(listOf("Now", "Week"), names(Filter("", due = Due.next(7)), tasks))
        assertEquals(listOf("Late", "Now", "Week"), names(Filter("", due = Due.next(7, includeOverdue = true)), tasks))
    }

    @Test
    fun `exact priority, and status open or done`() {
        val tasks = parseTasks("n.md", "- [ ] Open high ⏫\n- [x] Done high ⏫\n- [ ] Open highest 🔺")

        assertEquals(listOf("Open high"), names(Filter("", priority = Priority.HIGH..Priority.HIGH), tasks))
        assertEquals(listOf("Done high"), names(Filter("", status = Status.DONE, priority = Priority.HIGH..Priority.HIGH), tasks))
        assertEquals(3, names(Filter("", status = Status.ANY), tasks).size)
    }

    @Test
    fun `sorts by priority then due date, breaking ties by note and line`() {
        val tasks = parseTasks("b.md", "- [ ] B1 ⏫\n- [ ] B2 ⏫ 📅 2026-11-05") +
            parseTasks("a.md", "- [ ] A1 ⏫\n- [ ] A2 🔺")

        assertEquals(listOf("A2", "B2", "A1", "B1"), names(Filter("", sort = Sort.PRIORITY), tasks))
    }

    @Test
    fun `sorts by due date with undated tasks last`() {
        assertEquals(
            listOf("Review preprint #research", "Weekly review", "Order supplies", "Draft notes #research", "Clean kitchen"),
            names(Filter("All", sort = Sort.DUE)),
        )
    }

    @Test
    fun `saved filters read back unchanged`() {
        val filters = listOf(
            Filter("Due soon\tor high", due = Due.next(7, includeOverdue = true), priority = Priority.HIGH..Priority.HIGHEST, anyOf = true),
            Filter("Overdue", status = Status.ANY, due = Due.OVERDUE, sort = Sort.PRIORITY),
            Filter("Everything"),
        )

        assertEquals(
            listOf(filters[0].copy(name = "Due soon or high"), filters[1], filters[2]),
            decodeFilters(encodeFilters(filters)),
        )
    }
}
