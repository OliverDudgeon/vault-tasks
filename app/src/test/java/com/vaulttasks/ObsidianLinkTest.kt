package com.vaulttasks

import org.junit.Assert.assertEquals
import org.junit.Test

class ObsidianLinkTest {
    @Test
    fun `builds native open URIs with block id, heading, or plain note fallback`() {
        assertEquals(
            ObsidianLink("open", listOf("vault" to "Physics")),
            obsidianLink("Physics"),
        )
        assertEquals(
            ObsidianLink("open", listOf("vault" to "Physics", "file" to "Projects/Plan.md")),
            obsidianLink("Physics", path = "Projects/Plan.md"),
        )
        assertEquals(
            ObsidianLink("open", listOf("vault" to "Physics", "file" to "Projects/Plan#^t1")),
            obsidianLink("Physics", path = "Projects/Plan.md", line = 9, blockId = "t1", heading = "Tasks"),
        )
        assertEquals(
            ObsidianLink("open", listOf("vault" to "Physics", "file" to "Projects/Plan#Tasks")),
            obsidianLink("Physics", path = "Projects/Plan.md", line = 9, heading = "Tasks"),
        )
    }

    @Test
    fun `uses Advanced URI with 1-based line when available`() {
        assertEquals(
            ObsidianLink("advanced-uri", listOf("vault" to "Physics", "filepath" to "Projects/Plan", "line" to "10")),
            obsidianLink("Physics", path = "Projects/Plan.md", line = 9, blockId = "t1", heading = "Tasks", useAdvancedUri = true),
        )
        // Note heading taps have no line and still use native obsidian://open.
        assertEquals(
            ObsidianLink("open", listOf("vault" to "Physics", "file" to "Projects/Plan.md")),
            obsidianLink("Physics", path = "Projects/Plan.md", useAdvancedUri = true),
        )
    }
}
