package com.vaulttasks

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

class GroupTasksTest {
    private val today = LocalDate.of(2026, 11, 2) // A Monday.

    // Already in the filter's order; files are vault paths.
    private val tasks = parseTasks("Projects/Research.md", "- [ ] Draft 📅 2026-11-04\n- [ ] Review 📅 2026-11-02") +
        parseTasks("Inbox.md", "- [ ] Call 📅 2026-11-01\n- [ ] Undated") +
        parseTasks("Personal/Household/Tasks.md", "- [ ] Order 📅 2027-01-04\n- [ ] Clean 📅 2026-11-02") +
        parseTasks("Projects/Ideas.md", "- [ ] Sketch")

    private fun groups(grouping: GroupBy) =
        groupTasks(tasks, grouping, today, { it.file }, Locale.UK).map { g -> listOf(g.title, g.detail) to g.tasks.map { it.description } }

    @Test
    fun `no grouping keeps one untitled group in the filter's order`() {
        assertEquals(
            listOf(listOf(null, null) to listOf("Draft", "Review", "Call", "Undated", "Order", "Clean", "Sketch")),
            groups(GroupBy.NONE),
        )
    }

    @Test
    fun `groups by note, sorted by path, titled by note name with its folder`() {
        assertEquals(
            listOf(
                listOf("Inbox", null) to listOf("Call", "Undated"),
                listOf("Tasks", "Personal/Household") to listOf("Order", "Clean"),
                listOf("Ideas", "Projects") to listOf("Sketch"),
                listOf("Research", "Projects") to listOf("Draft", "Review"),
            ),
            groups(GroupBy.NOTE),
        )
    }

    @Test
    fun `groups by folder, the vault's top level first`() {
        assertEquals(
            listOf(
                listOf("Vault root", null) to listOf("Call", "Undated"),
                listOf("Personal/Household", null) to listOf("Order", "Clean"),
                listOf("Projects", null) to listOf("Draft", "Review", "Sketch"),
            ),
            groups(GroupBy.FOLDER),
        )
    }

    @Test
    fun `agenda shows dated tasks under the day they're due, earliest first`() {
        assertEquals(
            listOf(
                listOf("Yesterday", "Sun 1 Nov") to listOf("Call"),
                listOf("Today", "Mon 2 Nov") to listOf("Review", "Clean"),
                listOf("Wed", "4 Nov") to listOf("Draft"),
                listOf("Mon", "4 Jan 2027") to listOf("Order"),
            ),
            groups(GroupBy.AGENDA),
        )
    }
}
