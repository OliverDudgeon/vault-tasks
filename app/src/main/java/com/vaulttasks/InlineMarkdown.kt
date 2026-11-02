package com.vaulttasks

enum class Style { BOLD, ITALIC, STRIKE, HIGHLIGHT, CODE, LINK }

/** [start] until [end] (exclusive) of [Styled.text] has [style]. */
data class Span(val start: Int, val end: Int, val style: Style)

/** Display text with its styles, outer spans before the spans nested in them. */
data class Styled(val text: String, val spans: List<Span>)

/**
 * Obsidian's inline Markdown in a task description, for display (T-07): the markers go and their styles stay.
 * Links show their text, wikilinks show as Obsidian shows them (`[[Note#Plans]]` → "Note > Plans", aliases win).
 * The note itself is never changed. ponytail: no HTML, footnotes or inline maths; they show as written.
 */
fun inlineMarkdown(markdown: String): Styled {
    val out = StringBuilder()
    val spans = mutableListOf<Span>()
    fun render(s: String) {
        var i = 0
        while (i < s.length) {
            if (s[i] == '\\' && i + 1 < s.length && !s[i + 1].isLetterOrDigit() && !s[i + 1].isWhitespace()) {
                out.append(s[i + 1])
                i += 2
                continue
            }
            // `_` emphasis can't start inside a word, so snake_case stays as it is.
            val hit = INLINE.firstNotNullOfOrNull { (regex, styles) ->
                regex.matchAt(s, i)?.takeUnless { s[i] == '_' && i > 0 && s[i - 1].isLetterOrDigit() }?.let { it to styles }
            }
            if (hit == null) {
                out.append(s[i++])
                continue
            }
            val (match, styles) = hit
            val start = out.length
            val first = spans.size
            when (styles.first()) {
                Style.CODE -> out.append(match.groupValues[2])
                Style.LINK -> if (match.groupValues[1] == "[[") {
                    val (_, _, page, heading, alias) = match.groupValues
                    out.append(alias.ifEmpty { listOf(page, heading).filter { it.isNotEmpty() }.joinToString(" > ") })
                } else render(match.groupValues[1])
                else -> render(match.groupValues[1])
            }
            for (style in styles.reversed()) spans.add(first, Span(start, out.length, style))
            i = match.range.last + 1
        }
    }
    render(markdown)
    return Styled(out.toString(), spans)
}

private fun emphasis(marker: String) = Regex.escape(marker).let { m -> Regex("$m(?=\\S)(.+?)(?<=\\S)$m(?!${Regex.escape(marker.take(1))})") }

// Tried in order at each position; the first to match wins.
private val INLINE: List<Pair<Regex, List<Style>>> = listOf(
    Regex("(`+)(.+?)\\1") to listOf(Style.CODE),
    Regex("!?(\\[\\[)([^\\]|#]*)(?:#([^\\]|]*))?(?:\\|([^\\]]*))?]]") to listOf(Style.LINK),
    Regex("!?\\[([^\\]]+)]\\([^)]*\\)") to listOf(Style.LINK),
    emphasis("***") to listOf(Style.BOLD, Style.ITALIC),
    emphasis("**") to listOf(Style.BOLD),
    emphasis("__") to listOf(Style.BOLD),
    emphasis("*") to listOf(Style.ITALIC),
    emphasis("_") to listOf(Style.ITALIC),
    emphasis("~~") to listOf(Style.STRIKE),
    emphasis("==") to listOf(Style.HIGHLIGHT),
)
