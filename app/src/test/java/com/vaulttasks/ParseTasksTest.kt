package com.vaulttasks

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class ParseTasksTest {
    @Test
    fun `finds tasks in a note with their source line`() {
        val note = """
            # Research
            - [ ] Review preprint #research 🔺 📅 2026-11-02
            - [ ] Draft notes #research ⏳ 2026-11-03 📅 2026-11-07
        """.trimIndent()

        assertEquals(
            listOf(
                Task("Projects/Research.md", 1, "- [ ] Review preprint #research 🔺 📅 2026-11-02", note.hashCode(), "Research"),
                Task("Projects/Research.md", 2, "- [ ] Draft notes #research ⏳ 2026-11-03 📅 2026-11-07", note.hashCode(), "Research"),
            ),
            parseTasks("Projects/Research.md", note),
        )
    }

    @Test
    fun `finds nested, ordered and CRLF tasks but skips code fences`() {
        val note = "- [ ] parent\r\n" +
            "    * [x] nested\r\n" +
            "```\r\n" +
            "- [ ] example in a fence\r\n" +
            "```\r\n" +
            "1. [ ] ordered\r\n" +
            "~~~md\r\n" +
            "- [ ] tilde fence\r\n" +
            "~~~\r\n" +
            "- not a task\r\n" +
            "+ [ ] plus"

        assertEquals(
            listOf(
                Task("n.md", 0, "- [ ] parent", note.hashCode()),
                Task("n.md", 1, "    * [x] nested", note.hashCode()),
                Task("n.md", 5, "1. [ ] ordered", note.hashCode()),
                Task("n.md", 10, "+ [ ] plus", note.hashCode()),
            ),
            parseTasks("n.md", note),
        )
    }

    @Test
    fun `reads due date, scheduled date and priority from the metadata`() {
        val task = parseTasks("r.md", "- [ ] Draft notes #research ⏫ ⏳ 2026-11-03 📅 2026-11-07").single()

        assertEquals("Draft notes #research", task.description)
        assertEquals(LocalDate.of(2026, 11, 7), task.due)
        assertEquals(LocalDate.of(2026, 11, 3), task.scheduled)
        assertEquals(Priority.HIGH, task.priority)
        assertEquals(false, task.done)
        assertEquals(false, task.recurring)
    }

    @Test
    fun `reads fields in any order, keeping tags mixed among them on the description`() {
        val task = parseTasks("n.md", "- [ ] Order supplies 📅 2026-11-06 #home ⏫️ ✅ 2026-11-05 ^supplies").single()

        assertEquals("Order supplies #home", task.description)
        assertEquals(LocalDate.of(2026, 11, 6), task.due)
        assertEquals(Priority.HIGH, task.priority)
    }

    @Test
    fun `leaves a malformed date out of the due date`() {
        val task = parseTasks("n.md", "- [ ] Pay rent 📅 2026-02-30").single()

        assertEquals("Pay rent", task.description)
        assertEquals(null, task.due)
    }

    @Test
    fun `flags recurring tasks and treats cancelled ones as done`() {
        val (weekly, cancelled) = parseTasks("n.md", "- [ ] Weekly review 🔁 every week 📅 2026-11-02\n- [-] Old plan 🔽")

        assertEquals(true, weekly.recurring)
        assertEquals(LocalDate.of(2026, 11, 2), weekly.due)
        assertEquals(Priority.NONE, weekly.priority)
        assertEquals(true, cancelled.done)
        assertEquals(Priority.LOW, cancelled.priority)
    }

    @Test
    fun `reads start, created, done and cancelled dates and the recurrence rule`() {
        val task = parseTasks("n.md", "- [x] Water plants 🔁 every week when done 🛫 2026-11-01 ➕ 2026-10-01 ✅ 2026-11-02 ❌ 2026-11-03 ^w1  ").single()

        assertEquals("Water plants", task.description)
        assertEquals(LocalDate.of(2026, 11, 1), task.start)
        assertEquals(LocalDate.of(2026, 10, 1), task.created)
        assertEquals(LocalDate.of(2026, 11, 2), task.doneDate)
        assertEquals(LocalDate.of(2026, 11, 3), task.cancelledDate)
        assertEquals("every week when done", task.recurrence)
    }

    @Test
    fun `gives statuses the plugin's default types, unknown symbols being to-do`() {
        val types = parseTasks("n.md", "- [ ] a\n- [/] b\n- [x] c\n- [X] d\n- [-] e\n- [?] f").map { it.type }

        assertEquals(
            listOf(StatusType.TODO, StatusType.IN_PROGRESS, StatusType.DONE, StatusType.DONE, StatusType.CANCELLED, StatusType.TODO),
            types,
        )
    }

    @Test
    fun `finds tags anywhere in the description`() {
        val task = parseTasks("n.md", "- [ ] Call #work/admin about #tax ⏫ #urgent").single()

        assertEquals(listOf("#work/admin", "#tax", "#urgent"), task.tags)
    }

    @Test
    fun `takes the leftmost of repeated fields, as the plugin does`() {
        val task = parseTasks("n.md", "- [ ] Twice 🔺 📅 2026-11-01 ⏬ 📅 2026-12-01").single()

        assertEquals(Priority.HIGHEST, task.priority)
        assertEquals(LocalDate.of(2026, 11, 1), task.due)
    }

    @Test
    fun `tracks nearest heading and block id while ignoring frontmatter and fenced headings`() {
        val note = """
            ---
            # YAML comment, not a heading
            title: Demo
            ---
            - [ ] Before any heading
            # **Top** [[Project|Plan]] ##
            ```
            ## Not a heading
            ```
            - [ ] Under top heading ^top-1  
            ### Sub #section
            - [ ] Under subheading
        """.trimIndent()

        val parsed = parseTasks("Demo.md", note)
        assertEquals(listOf(null, "Top Plan", "Sub section"), parsed.map { it.heading })
        assertEquals(listOf(null, "top-1", null), parsed.map { it.blockId })
    }
}
