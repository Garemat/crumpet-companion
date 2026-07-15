package io.github.garemat.crumpet.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/** The subset of markdown Crumpet actually uses in chat, rendered instead of shown raw
 *  (field report: "**Wisdom**" displayed with its asterisks). Inline only — **bold**,
 *  *italic*, `code` — plus line-level tidying: headers lose their #s and render bold,
 *  "- "/"* " bullets become "• ", [label](url) links keep their label. Anything
 *  fancier is out of scope for a chat bubble; unmatched marks render as typed. */

private val LINK = Regex("""\[([^\]]+)]\([^)]*\)""")
private val HEADER = Regex("""^\s*#{1,6}\s+""")
private val BULLET = Regex("""^(\s*)[-*]\s+""")
private val INLINE = Regex("""\*\*([^*\n]+)\*\*|\*([^*\n]+)\*|`([^`\n]+)`""")

private val CODE_BG = Color(0x33FFFFFF)

fun inlineMarkdown(raw: String): AnnotatedString = buildAnnotatedString {
    raw.lines().forEachIndexed { i, rawLine ->
        if (i > 0) append("\n")
        val line = LINK.replace(rawLine) { it.groupValues[1] }
        if (HEADER.containsMatchIn(line)) {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                appendInline(HEADER.replace(line, ""))
            }
        } else {
            appendInline(BULLET.replace(line) { "${it.groupValues[1]}• " })
        }
    }
}

private fun AnnotatedString.Builder.appendInline(line: String) {
    var i = 0
    for (m in INLINE.findAll(line)) {
        append(line.substring(i, m.range.first))
        val bold = m.groups[1]?.value
        val italic = m.groups[2]?.value
        val code = m.groups[3]?.value
        when {
            bold != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(bold) }
            italic != null -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(italic) }
            code != null -> withStyle(
                SpanStyle(fontFamily = FontFamily.Monospace, background = CODE_BG),
            ) { append(code) }
        }
        i = m.range.last + 1
    }
    append(line.substring(i))
}
