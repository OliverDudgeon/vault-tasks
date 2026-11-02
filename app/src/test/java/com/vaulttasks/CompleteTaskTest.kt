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
    fun `AT-08 puts the next occurrence of a recurring task above the completed one`() {
        val note = "# Household\r\n  - [ ] Weekly review 🔁 every week 📅 2026-11-02\r\n"
        val weekly = parseTasks("t.md", note).single()

        assertEquals(
            Completed(
                "# Household\r\n" +
                    "  - [ ] Weekly review 🔁 every week 📅 2026-11-09\r\n" +
                    "  - [x] Weekly review 🔁 every week 📅 2026-11-02 ✅ 2026-11-02\r\n",
            ),
            completeTask(note, weekly, today),
        )
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
    fun `stamps the done date before a trailing block id and keeps trailing whitespace`() {
        val task = Task("t.md", 0, "- [ ] Order supplies 📅 2026-11-06 ^supplies  ")

        assertEquals(
            Completed("- [x] Order supplies 📅 2026-11-06 ✅ 2026-11-02 ^supplies  "),
            completeTask(task.text, task, today),
        )
    }

    @Test
    fun `completes the scanned one of two identical tasks while the note is unchanged`() {
        val note = "## Monday\n- [ ] Water plants\n## Thursday\n- [ ] Water plants\n"
        val thursday = parseTasks("t.md", note)[1]

        assertEquals(
            Completed("## Monday\n- [ ] Water plants\n## Thursday\n- [x] Water plants ✅ 2026-11-02\n"),
            completeTask(note, thursday, today),
        )
    }

    @Test
    fun `completes an in-progress task`() {
        val task = Task("t.md", 0, "- [/] Write report")

        assertEquals(Completed("- [x] Write report ✅ 2026-11-02"), completeTask(task.text, task, today))
    }

    private fun nextLine(line: String, on: LocalDate = today): String {
        val task = parseTasks("t.md", line).single()
        return (completeTask(line, task, on) as Completed).content.lines().first()
    }

    @Test
    fun `computes the next date like the plugin's recurrence fixtures`() {
        // From obsidian-tasks tests/Task/Recurrence.test.ts, plus weekday and month-day rules checked against rrule.
        val cases = listOf(
            "every month" to ("2022-01-31" to "2022-02-28"),
            "every 3 months" to ("2022-01-31" to "2022-04-30"),
            "every 2 months" to ("2023-12-31" to "2024-02-29"),
            "every 2 years" to ("2024-02-29" to "2026-02-28"),
            "every 11 months" to ("2020-03-31" to "2021-02-28"),
            "every 13 months" to ("2020-01-31" to "2021-02-28"),
            "every day" to ("2026-11-02" to "2026-11-03"),
            "every 2 weeks" to ("2026-11-02" to "2026-11-16"),
            "every weekday" to ("2026-11-06" to "2026-11-09"),
            "every Tuesday and Friday" to ("2026-11-03" to "2026-11-06"),
            "every 2 weeks on Monday, Wednesday" to ("2026-11-04" to "2026-11-16"),
            "every month on the 15th" to ("2026-11-20" to "2026-12-15"),
            "every month on the 31st" to ("2026-10-31" to "2026-12-31"),
            "every month on the last" to ("2026-01-31" to "2026-02-28"),
        )
        for ((rule, dates) in cases) {
            assertEquals(rule, "- [ ] Pay 🔁 $rule 📅 ${dates.second}", nextLine("- [ ] Pay 🔁 $rule 📅 ${dates.first}"))
        }
    }

    @Test
    fun `keeps the other dates' distance from the due date, or from today when done`() {
        assertEquals(
            "- [ ] Plan 🛫 2026-11-07 ⏳ 2026-11-08 📅 2026-11-09 🔁 every week",
            nextLine("- [ ] Plan 🛫 2026-10-31 ⏳ 2026-11-01 📅 2026-11-02 🔁 every week"),
        )
        assertEquals(
            "- [ ] Mow ⏳ 2026-11-12 🔁 every 10 days when done",
            nextLine("- [ ] Mow ⏳ 2026-10-01 🔁 every 10 days when done"),
        )
    }

    @Test
    fun `drops done, created, id, dependency and block id fields from the next occurrence only`() {
        val note = "- [/] Report #work ➕ 2026-10-01 🆔 rep ⛔ data 🔁 every month 📅 2026-11-02 ^rep  "

        assertEquals(
            Completed(
                "- [ ] Report #work 🔁 every month 📅 2026-12-02\n" +
                    "- [x] Report #work ➕ 2026-10-01 🆔 rep ⛔ data 🔁 every month 📅 2026-11-02 ✅ 2026-11-02 ^rep  ",
            ),
            completeTask(note, parseTasks("t.md", note).single(), today),
        )
    }

    @Test
    fun `removes the completed line when it says to delete on completion`() {
        val note = "a\n- [ ] Stretch 🏁 delete 🔁 every day 📅 2026-11-02\n- [ ] Read 🏁 delete\nb"
        val (stretch, read) = parseTasks("t.md", note)

        val afterStretch = (completeTask(note, stretch, today) as Completed).content
        assertEquals("a\n- [ ] Stretch 🏁 delete 🔁 every day 📅 2026-11-03\n- [ ] Read 🏁 delete\nb", afterStretch)
        assertEquals(Completed("a\n- [ ] Stretch 🏁 delete 🔁 every day 📅 2026-11-02\nb"), completeTask(note, read, today))
    }

    @Test(timeout = 5_000)
    fun `refuses recurring tasks it can't follow rather than lose the next occurrence`() {
        for (line in listOf(
            "- [ ] Bins 🔁 every month on the 2nd Tuesday 📅 2026-11-10",
            "- [ ] Rent 🔁 every month 📅 2026-02-30",
            "- [ ] Odd 🔁 every fortnight",
            "- [ ] Never 🔁 every 12 months on the 30th 📅 2026-02-10",
        )) {
            assertEquals(line, Unsupported, completeTask(line, parseTasks("t.md", line).single(), today))
        }
    }

    @Test
    fun `refuses custom statuses, which the vault may define as done or cancelled`() {
        val forwarded = Task("t.md", 0, "- [>] Call back")

        assertEquals(Unsupported, completeTask(forwarded.text, forwarded, today))
    }
}
