package com.oceanguard.ai.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * Renders a Markdown-formatted string with proper styling in Compose.
 *
 * Supported syntax:
 * - `# H1`, `## H2`, `### H3` headings
 * - `**bold**` and `*italic*`
 * - `---` horizontal dividers
 * - `> blockquotes`
 * - `1.` numbered lists
 * - Regular paragraphs
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(text) { parseMarkdownBlocks(text) }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.H1 -> {
                    Text(
                        text = parseInlineFormatting(block.content),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                is MarkdownBlock.H2 -> {
                    Text(
                        text = parseInlineFormatting(block.content),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                is MarkdownBlock.H3 -> {
                    Text(
                        text = parseInlineFormatting(block.content),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                is MarkdownBlock.Divider -> {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }

                is MarkdownBlock.Blockquote -> {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
                    ) {
                        Row(modifier = Modifier.padding(12.dp)) {
                            Surface(
                                modifier = Modifier
                                    .padding(end = 10.dp)
                                    .fillMaxWidth(0.008f),
                                color = MaterialTheme.colorScheme.tertiary,
                                content = {},
                            )
                            Text(
                                text = parseInlineFormatting(block.content),
                                style = MaterialTheme.typography.bodyMedium,
                                fontStyle = FontStyle.Italic,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                        }
                    }
                }

                is MarkdownBlock.ListItem -> {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "${block.number}. ",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = parseInlineFormatting(block.content),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                is MarkdownBlock.Paragraph -> {
                    Text(
                        text = parseInlineFormatting(block.content),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                is MarkdownBlock.Spacer -> {
                    // Empty line — small vertical gap handled by Column spacing
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Block-level parsing
// ---------------------------------------------------------------------------

private sealed class MarkdownBlock {
    data class H1(val content: String) : MarkdownBlock()
    data class H2(val content: String) : MarkdownBlock()
    data class H3(val content: String) : MarkdownBlock()
    data object Divider : MarkdownBlock()
    data class Blockquote(val content: String) : MarkdownBlock()
    data class ListItem(val number: Int, val content: String) : MarkdownBlock()
    data class Paragraph(val content: String) : MarkdownBlock()
    data object Spacer : MarkdownBlock()
}

private val ORDERED_LIST_REGEX = Regex("""^(\d+)\.\s+(.+)""")

private fun parseMarkdownBlocks(text: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val lines = text.lines()

    var i = 0
    while (i < lines.size) {
        val line = lines[i].trimEnd()

        when {
            // Empty line
            line.isBlank() -> {
                blocks.add(MarkdownBlock.Spacer)
                i++
            }

            // Horizontal rule
            line.matches(Regex("""^-{3,}\s*$""")) -> {
                blocks.add(MarkdownBlock.Divider)
                i++
            }

            // H3 (must check before H2 and H1)
            line.startsWith("### ") -> {
                blocks.add(MarkdownBlock.H3(line.removePrefix("### ").trim()))
                i++
            }

            // H2
            line.startsWith("## ") -> {
                blocks.add(MarkdownBlock.H2(line.removePrefix("## ").trim()))
                i++
            }

            // H1
            line.startsWith("# ") -> {
                blocks.add(MarkdownBlock.H1(line.removePrefix("# ").trim()))
                i++
            }

            // Blockquote — accumulate consecutive > lines
            line.startsWith("> ") || line.startsWith(">") -> {
                val quoteLines = mutableListOf<String>()
                while (i < lines.size && (lines[i].trimEnd().startsWith("> ") || lines[i].trimEnd().startsWith(">"))) {
                    quoteLines.add(lines[i].trimEnd().removePrefix("> ").removePrefix(">").trim())
                    i++
                }
                blocks.add(MarkdownBlock.Blockquote(quoteLines.joinToString(" ")))
            }

            // Ordered list item
            ORDERED_LIST_REGEX.matches(line) -> {
                val match = ORDERED_LIST_REGEX.find(line)!!
                val number = match.groupValues[1].toInt()
                // Accumulate continuation lines (indented or same paragraph)
                val contentLines = mutableListOf(match.groupValues[2])
                i++
                while (i < lines.size) {
                    val nextLine = lines[i].trimEnd()
                    // Stop if next line is empty, a new block, or a new list item
                    if (nextLine.isBlank() || nextLine.startsWith("#") ||
                        nextLine.startsWith("---") || nextLine.startsWith("> ") ||
                        ORDERED_LIST_REGEX.matches(nextLine)
                    ) break
                    contentLines.add(nextLine.trim())
                    i++
                }
                blocks.add(MarkdownBlock.ListItem(number, contentLines.joinToString(" ")))
            }

            // Regular paragraph — accumulate consecutive non-empty lines
            else -> {
                val paragraphLines = mutableListOf<String>()
                while (i < lines.size) {
                    val nextLine = lines[i].trimEnd()
                    if (nextLine.isBlank() || nextLine.startsWith("#") ||
                        nextLine.startsWith("---") || nextLine.startsWith("> ") ||
                        ORDERED_LIST_REGEX.matches(nextLine)
                    ) break
                    paragraphLines.add(nextLine)
                    i++
                }
                blocks.add(MarkdownBlock.Paragraph(paragraphLines.joinToString(" ")))
            }
        }
    }

    return blocks
}

// ---------------------------------------------------------------------------
// Inline formatting (**bold** and *italic*)
// ---------------------------------------------------------------------------

private val INLINE_PATTERN = Regex("""\*\*(.+?)\*\*|\*(.+?)\*""")

private fun parseInlineFormatting(text: String): AnnotatedString {
    return buildAnnotatedString {
        var lastIndex = 0

        INLINE_PATTERN.findAll(text).forEach { matchResult ->
            // Append text before this match
            if (matchResult.range.first > lastIndex) {
                append(text.substring(lastIndex, matchResult.range.first))
            }

            val boldContent = matchResult.groupValues[1]   // **bold**
            val italicContent = matchResult.groupValues[2]  // *italic*

            if (boldContent.isNotEmpty()) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(boldContent)
                }
            } else if (italicContent.isNotEmpty()) {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(italicContent)
                }
            }

            lastIndex = matchResult.range.last + 1
        }

        // Append remaining text
        if (lastIndex < text.length) {
            append(text.substring(lastIndex))
        }
    }
}
