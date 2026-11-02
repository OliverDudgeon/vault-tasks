package com.vaulttasks

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class CompleteInNoteTest {
    private val today = LocalDate.of(2026, 11, 2)
    private val task = Task("t.md", 0, "- [ ] Order supplies ⏫ 📅 2026-11-06")
    private val written = mutableListOf<String>()

    @Test
    fun `writes the completed note`() {
        val result = completeInNote(task, today, read = { task.text }, write = { written += it })

        assertEquals(Completed("- [x] Order supplies ⏫ 📅 2026-11-06 ✅ 2026-11-02"), result)
        assertEquals(listOf("- [x] Order supplies ⏫ 📅 2026-11-06 ✅ 2026-11-02"), written)
    }

    @Test
    fun `does not write when the note changes while the edit is being prepared`() {
        val reads = ArrayDeque(listOf(task.text, task.text + "\n- [ ] Synced in meanwhile"))

        val result = completeInNote(task, today, read = { reads.removeFirst() }, write = { written += it })

        assertEquals(Conflict, result)
        assertEquals(emptyList<String>(), written)
    }
}
