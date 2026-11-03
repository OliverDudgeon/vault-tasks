package com.vaulttasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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

    private fun query(text: String, tasks: List<Task> = vault) = names(Filter("", status = Status.ANY, query = text), tasks)

    @Test
    fun `AT-04 as a Tasks query, with every line having to match`() {
        assertEquals(
            listOf("Review preprint #research", "Weekly review", "Order supplies"),
            query("not done\n(due today) OR (priority is above medium)"),
        )
    }

    @Test
    fun `date filters take the plugin's keywords, relative dates and ranges`() {
        val tasks = parseTasks(
            "n.md",
            "- [ ] Late 📅 2026-10-30\n- [ ] Now ⏳ 2026-11-03 📅 2026-11-02\n- [ ] Week 🛫 2026-11-04 📅 2026-11-09\n- [x] Undated ✅ 2026-11-01",
        )

        assertEquals(listOf("Late", "Now"), query("due before tomorrow", tasks))
        assertEquals(listOf("Week"), query("due after in 5 days", tasks))
        assertEquals(listOf("Now", "Week"), query("due on or after today", tasks))
        assertEquals(listOf("Now"), query("due this week", tasks))
        assertEquals(listOf("Week"), query("due next week", tasks))
        assertEquals(listOf("Late"), query("due 2026-10", tasks))
        assertEquals(listOf("Now", "Week"), query("due in 2026-11-01 2026-11-30", tasks))
        assertEquals(listOf("Now"), query("scheduled on 2026-11-03", tasks))
        assertEquals(listOf("Undated"), query("done yesterday", tasks))
        assertEquals(listOf("Undated"), query("no due date", tasks))
        // A task with no start date matches every starts filter, as in the plugin.
        assertEquals(listOf("Late", "Now", "Undated"), query("starts before 2026-11-04", tasks))
        assertEquals(listOf("Now", "Week"), query("happens on or after 2026-11-03", tasks))
    }

    @Test
    fun `text, path and tag filters are case-insensitive`() {
        assertEquals(listOf("Review preprint #research", "Draft notes #research"), query("tags include #RESEARCH\nsort by path"))
        assertEquals(listOf("Weekly review", "Order supplies", "Clean kitchen"), query("path includes household"))
        assertEquals(listOf("Draft notes #research"), query("description regex matches /^draft/i"))
        assertEquals(listOf("Order supplies", "Clean kitchen"), query("description does not include review\nfilename includes tasks.md"))
        assertEquals(listOf("Clean kitchen"), query("no tags\nno due date"))
    }

    @Test
    fun `filters on whether a task has an id`() {
        val tasks = parseTasks("n.md", "- [ ] Root 🆔 abc123\n- [ ] Blocked ⛔ abc123\n- [ ] Plain\n- [x] Done 🆔 d-1 ✅ 2026-11-01")

        assertEquals(listOf("Root", "Done"), query("has id", tasks))
        assertEquals(listOf("Blocked", "Plain"), query("no id", tasks))
        assertEquals("abc123", tasks.first().id)
    }

    @Test
    fun `boolean combinations nest and negate`() {
        assertEquals(listOf("Weekly review", "Order supplies", "Clean kitchen"), query("NOT (path includes Projects)"))
        assertEquals(
            listOf("Review preprint #research", "Weekly review"),
            query("((due today) AND (is recurring)) XOR (priority is highest)"),
        )
        assertEquals(listOf("Review preprint #research"), query("((due today) AND (is recurring)) XOR (due today)"))
        // The plugin evaluates NOT, then XOR, then AND, then OR.
        assertEquals(listOf("Weekly review"), query("(priority is highest) XOR (due today) AND (is recurring)"))
    }

    @Test
    fun `filters on the status type`() {
        val tasks = parseTasks("n.md", "- [ ] Todo\n- [/] Doing\n- [-] Dropped\n- [?] Custom")

        assertEquals(listOf("Doing"), query("status.type is IN_PROGRESS", tasks))
        assertEquals(listOf("Todo", "Custom"), query("status.type is TODO", tasks))
        assertEquals(listOf("Todo", "Doing", "Custom"), query("not done", tasks))
        assertEquals(listOf("Doing", "Todo", "Custom", "Dropped"), query("sort by status.type", tasks))
    }

    @Test
    fun `sort lines come first, then the filter's own order, and limit cuts the result`() {
        assertEquals(listOf("Weekly review", "Review preprint #research"), query("sort by description reverse\nlimit to 2 tasks"))
    }

    @Test
    fun `ignores layout lines and comments but names a line it doesn't understand`() {
        assertEquals(5, query("# everything\nhide due date\nshort mode\ngroup by path").size)

        val error = assertThrows(QueryError::class.java) { query("not done\nheading includes Plans") }
        assertEquals("Can't use the query line \"heading includes Plans\"", error.message)
    }

    @Test
    fun `saved queries keep their line breaks and tabs`() {
        val filter = Filter("Q", query = "not done\n\tpath includes a\\nb")

        assertEquals(listOf(filter), decodeFilters(encodeFilters(listOf(filter))))
    }
}
