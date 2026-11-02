package com.vaulttasks

import com.vaulttasks.Style.BOLD
import com.vaulttasks.Style.CODE
import com.vaulttasks.Style.HIGHLIGHT
import com.vaulttasks.Style.ITALIC
import com.vaulttasks.Style.LINK
import com.vaulttasks.Style.STRIKE
import org.junit.Assert.assertEquals
import org.junit.Test

class InlineMarkdownTest {
    private fun styled(markdown: String) = inlineMarkdown(markdown).let { s -> s.text to s.spans.map { s.text.substring(it.start, it.end) to it.style } }

    @Test
    fun `styles emphasis, strikethrough, highlight and code`() {
        assertEquals(
            "bold, it, it, gone, hi, x*y*" to listOf("bold" to BOLD, "it" to ITALIC, "it" to ITALIC, "gone" to STRIKE, "hi" to HIGHLIGHT, "x*y*" to CODE),
            styled("**bold**, *it*, _it_, ~~gone~~, ==hi==, `x*y*`"),
        )
    }

    @Test
    fun `shows links by their text, wikilinks as Obsidian does`() {
        assertEquals(
            "Note, alias, Note > Plans, site, pic.png" to listOf("Note" to LINK, "alias" to LINK, "Note > Plans" to LINK, "site" to LINK, "pic.png" to LINK),
            styled("[[Note]], [[Folder/Note|alias]], [[Note#Plans]], [site](https://example.com), ![[pic.png]]"),
        )
    }

    @Test
    fun `nests styles`() {
        assertEquals(
            "see Note now" to listOf("see Note now" to BOLD, "Note" to LINK, "now" to ITALIC),
            styled("**see [[Note]] *now***"),
        )
        assertEquals("both" to listOf("both" to BOLD, "both" to ITALIC), styled("***both***"))
    }

    @Test
    fun `leaves escapes, unclosed markers and snake_case as text`() {
        assertEquals("*not* **open snake_case_name 2 * 3" to emptyList<Pair<String, Style>>(), styled("\\*not\\* **open snake_case_name 2 * 3"))
    }
}
