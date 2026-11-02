package com.vaulttasks

import org.junit.Assert.assertEquals
import org.junit.Test

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
                Task("Projects/Research.md", 1, "- [ ] Review preprint #research 🔺 📅 2026-11-02"),
                Task("Projects/Research.md", 2, "- [ ] Draft notes #research ⏳ 2026-11-03 📅 2026-11-07"),
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
                Task("n.md", 0, "- [ ] parent"),
                Task("n.md", 1, "    * [x] nested"),
                Task("n.md", 5, "1. [ ] ordered"),
                Task("n.md", 10, "+ [ ] plus"),
            ),
            parseTasks("n.md", note),
        )
    }
}
