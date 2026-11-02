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
                Task("Projects/Research.md", 1, "- [ ] Review preprint #research 🔺 📅 2026-11-02", note.hashCode()),
                Task("Projects/Research.md", 2, "- [ ] Draft notes #research ⏳ 2026-11-03 📅 2026-11-07", note.hashCode()),
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
}
