package com.vaulttasks

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class CompleteTaskTest {
    private val today = LocalDate.of(2026, 11, 2)
    private val preprint = Task("r.md", 1, "- [ ] Review preprint #research 🔺 📅 2026-11-02")

    @Test
    fun `checks only the task line and stamps the done date`() {
        val note = "# Research\r\n" +
            "- [ ] Review preprint #research 🔺 📅 2026-11-02\r\n" +
            "- [ ] Draft notes #research ⏳ 2026-11-03 📅 2026-11-07\r\n"

        assertEquals(
            Completed(
                "# Research\r\n" +
                    "- [x] Review preprint #research 🔺 📅 2026-11-02 ✅ 2026-11-02\r\n" +
                    "- [ ] Draft notes #research ⏳ 2026-11-03 📅 2026-11-07\r\n",
            ),
            completeTask(note, preprint, today),
        )
    }

    @Test
    fun `follows the task when lines were inserted above it after the scan`() {
        val note = "# Research\nAdded on desktop\n- [ ] Review preprint #research 🔺 📅 2026-11-02"

        assertEquals(
            Completed("# Research\nAdded on desktop\n- [x] Review preprint #research 🔺 📅 2026-11-02 ✅ 2026-11-02"),
            completeTask(note, preprint, today),
        )
    }

    @Test
    fun `refuses when the task line was edited after the scan`() {
        val note = "# Research\n- [ ] Review preprint #research 🔺 📅 2026-11-09"

        assertEquals(Conflict, completeTask(note, preprint, today))
    }

    @Test
    fun `refuses when the task moved and its text is no longer unique`() {
        val note = "# Research\n\n- [ ] Review preprint #research 🔺 📅 2026-11-02\n- [ ] Review preprint #research 🔺 📅 2026-11-02"

        assertEquals(Conflict, completeTask(note, preprint, today))
    }

    @Test
    fun `refuses recurring tasks rather than lose the next occurrence`() {
        val weekly = Task("t.md", 0, "- [ ] Weekly review 🔁 every week 📅 2026-11-02")

        assertEquals(Unsupported, completeTask(weekly.text, weekly, today))
    }

    @Test
    fun `refuses tasks that are not open`() {
        val done = Task("t.md", 0, "- [x] Fix the [ ] placeholder")

        assertEquals(Unsupported, completeTask(done.text, done, today))
    }

    @Test
    fun `refuses when an identical task shifted onto the expected line`() {
        // Scanned with the duplicate at lines 1 and 2; a line above was then deleted.
        val note = "- [ ] Review preprint #research 🔺 📅 2026-11-02\n- [ ] Review preprint #research 🔺 📅 2026-11-02"

        assertEquals(Conflict, completeTask(note, preprint, today))
    }

    @Test
    fun `stamps the done date before a trailing block id so links to the block survive`() {
        val task = Task("t.md", 0, "- [ ] Order supplies 📅 2026-11-06 ^supplies  ")

        assertEquals(
            Completed("- [x] Order supplies 📅 2026-11-06 ✅ 2026-11-02 ^supplies"),
            completeTask(task.text, task, today),
        )
    }
}
