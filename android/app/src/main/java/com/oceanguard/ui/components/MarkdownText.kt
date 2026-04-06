package com.oceanguard.ai.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import android.util.Log
import androidx.compose.ui.Alignment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
 * - `1.` numbered lists (ordered)
 * - `- ` / `* ` bullet lists (unordered)
 * - `| col | col |` markdown tables
 * - Regular paragraphs
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
) {
    // Parse on Default dispatcher — large reports (25K chars) block the main thread >10s
    // if parsed synchronously (ANR). produceState offloads it; loading indicator shown while
    // blocks are empty so the user knows content is coming (not a blank broken screen).
    val blocks by produceState(initialValue = emptyList<MdBlock>(), text) {
        value = try {
            withContext(Dispatchers.Default) { parseMarkdownBlocks(text) }
        } catch (e: Exception) {
            Log.w("MarkdownText", "Markdown parse failed, rendering raw text", e)
            listOf(MdBlock.Paragraph(text))
        }
    }

    if (blocks.isEmpty()) {
        if (text.isEmpty()) return
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(120.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(32.dp),
                strokeWidth = 2.dp,
            )
        }
        return
    }

    Column(
        modifier = modifier,
    ) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.H1 -> {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = parseInlineFormatting(block.content),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }

                is MdBlock.H2 -> {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = parseInlineFormatting(block.content),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }

                is MdBlock.H3 -> {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = parseInlineFormatting(block.content),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                }

                is MdBlock.Divider -> {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }

                is MdBlock.Blockquote -> {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
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

                is MdBlock.OrderedItem -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                    ) {
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

                is MdBlock.BulletItem -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(top = 8.dp, end = 8.dp)
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                        )
                        Text(
                            text = parseInlineFormatting(block.content),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                is MdBlock.Table -> {
                    MarkdownTable(block)
                }

                is MdBlock.Paragraph -> {
                    Text(
                        text = parseInlineFormatting(block.content),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(vertical = 3.dp),
                    )
                }

                is MdBlock.Gap -> {
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Table rendering
// ---------------------------------------------------------------------------

@Composable
private fun MarkdownTable(table: MdBlock.Table) {
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    val headerBg = MaterialTheme.colorScheme.surfaceContainerHigh
    val rowBg = MaterialTheme.colorScheme.surfaceContainerLow
    val altRowBg = MaterialTheme.colorScheme.surfaceContainer

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .horizontalScroll(rememberScrollState()),
    ) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, borderColor, RoundedCornerShape(8.dp)),
        ) {
            // Header row
            if (table.headers.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .background(headerBg)
                        .height(IntrinsicSize.Min),
                ) {
                    table.headers.forEachIndexed { colIdx, cell ->
                        Box(
                            modifier = Modifier
                                .width(TABLE_COL_WIDTH)
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                        ) {
                            Text(
                                text = parseInlineFormatting(cell),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (colIdx < table.headers.lastIndex) {
                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .height(IntrinsicSize.Max)
                                    .background(borderColor),
                            )
                        }
                    }
                }
                HorizontalDivider(color = borderColor)
            }

            // Data rows
            table.rows.forEachIndexed { rowIdx, row ->
                val bg = if (rowIdx % 2 == 0) rowBg else altRowBg
                Row(
                    modifier = Modifier
                        .background(bg)
                        .height(IntrinsicSize.Min),
                ) {
                    val colCount = table.headers.size.coerceAtLeast(row.size)
                    for (colIdx in 0 until colCount) {
                        val cell = row.getOrElse(colIdx) { "" }
                        Box(
                            modifier = Modifier
                                .width(TABLE_COL_WIDTH)
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Text(
                                text = parseInlineFormatting(cell),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (colIdx < colCount - 1) {
                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .height(IntrinsicSize.Max)
                                    .background(borderColor),
                            )
                        }
                    }
                }
                if (rowIdx < table.rows.lastIndex) {
                    HorizontalDivider(color = borderColor.copy(alpha = 0.5f))
                }
            }
        }
    }
}

private val TABLE_COL_WIDTH = 120.dp

// ---------------------------------------------------------------------------
// Block-level parsing
// ---------------------------------------------------------------------------

private sealed class MdBlock {
    data class H1(val content: String) : MdBlock()
    data class H2(val content: String) : MdBlock()
    data class H3(val content: String) : MdBlock()
    data object Divider : MdBlock()
    data class Blockquote(val content: String) : MdBlock()
    data class OrderedItem(val number: Int, val content: String) : MdBlock()
    data class BulletItem(val content: String) : MdBlock()
    data class Table(
        val headers: List<String>,
        val rows: List<List<String>>,
    ) : MdBlock()
    data class Paragraph(val content: String) : MdBlock()
    data object Gap : MdBlock()
}

private val HORIZONTAL_RULE_REGEX = Regex("""^-{3,}\s*$""")
private val ORDERED_LIST_REGEX = Regex("""^(\d+)\.\s+(.+)""")
private val UNORDERED_LIST_REGEX = Regex("""^[-*+]\s+(.+)""")
private val TABLE_ROW_REGEX = Regex("""^\|(.+)\|$""")
private val TABLE_SEPARATOR_REGEX = Regex("""^\|[\s:?-]+(\|[\s:?-]+)+\|$""")

private fun parseMarkdownBlocks(text: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val lines = text.lines()
    var i = 0
    while (i < lines.size) {
        val line = lines[i].trimEnd()

        when {
            // Empty line
            line.isBlank() -> {
                // Collapse consecutive blank lines into a single gap
                if (blocks.lastOrNull() !is MdBlock.Gap) {
                    blocks.add(MdBlock.Gap)
                }
                i++
            }

            // Horizontal rule
            HORIZONTAL_RULE_REGEX.matches(line) -> {
                blocks.add(MdBlock.Divider)
                i++
            }

            // H4+ (####, #####, …) — render as H3 since we don't have deeper styles
            line.startsWith("#### ") -> {
                blocks.add(MdBlock.H3(line.trimStart('#').trim()))
                i++
            }

            // H3 (must check before H2 and H1)
            line.startsWith("### ") -> {
                blocks.add(MdBlock.H3(line.removePrefix("### ").trim()))
                i++
            }

            // H2
            line.startsWith("## ") -> {
                blocks.add(MdBlock.H2(line.removePrefix("## ").trim()))
                i++
            }

            // H1
            line.startsWith("# ") -> {
                blocks.add(MdBlock.H1(line.removePrefix("# ").trim()))
                i++
            }

            // Blockquote — accumulate consecutive > lines
            line.startsWith("> ") || line == ">" -> {
                val quoteLines = mutableListOf<String>()
                while (i < lines.size) {
                    val ql = lines[i].trimEnd()
                    if (!ql.startsWith("> ") && ql != ">") break
                    quoteLines.add(ql.removePrefix("> ").removePrefix(">").trim())
                    i++
                }
                blocks.add(MdBlock.Blockquote(quoteLines.joinToString(" ")))
            }

            // Markdown table — detect header + separator + rows
            TABLE_ROW_REGEX.matches(line) && i + 1 < lines.size &&
                TABLE_SEPARATOR_REGEX.matches(lines[i + 1].trimEnd()) -> {
                val headers = parseTableCells(line)
                i += 2 // skip header + separator
                val rows = mutableListOf<List<String>>()
                while (i < lines.size && TABLE_ROW_REGEX.matches(lines[i].trimEnd())) {
                    rows.add(parseTableCells(lines[i].trimEnd()))
                    i++
                }
                blocks.add(MdBlock.Table(headers, rows))
            }

            // Ordered list item
            ORDERED_LIST_REGEX.matches(line) -> {
                val match = ORDERED_LIST_REGEX.find(line)!!
                val number = match.groupValues[1].toInt()
                val contentLines = mutableListOf(match.groupValues[2])
                i++
                while (i < lines.size) {
                    val nextLine = lines[i].trimEnd()
                    if (nextLine.isBlank() || nextLine.startsWith("#") ||
                        nextLine.startsWith("---") || nextLine.startsWith("> ") ||
                        ORDERED_LIST_REGEX.matches(nextLine) ||
                        UNORDERED_LIST_REGEX.matches(nextLine) ||
                        TABLE_ROW_REGEX.matches(nextLine)
                    ) break
                    contentLines.add(nextLine.trim())
                    i++
                }
                blocks.add(MdBlock.OrderedItem(number, contentLines.joinToString(" ")))
            }

            // Unordered list item
            UNORDERED_LIST_REGEX.matches(line) -> {
                val match = UNORDERED_LIST_REGEX.find(line)!!
                val contentLines = mutableListOf(match.groupValues[1])
                i++
                while (i < lines.size) {
                    val nextLine = lines[i].trimEnd()
                    if (nextLine.isBlank() || nextLine.startsWith("#") ||
                        nextLine.startsWith("---") || nextLine.startsWith("> ") ||
                        ORDERED_LIST_REGEX.matches(nextLine) ||
                        UNORDERED_LIST_REGEX.matches(nextLine) ||
                        TABLE_ROW_REGEX.matches(nextLine)
                    ) break
                    contentLines.add(nextLine.trim())
                    i++
                }
                blocks.add(MdBlock.BulletItem(contentLines.joinToString(" ")))
            }

            // Regular paragraph — accumulate consecutive non-empty lines
            else -> {
                val paragraphLines = mutableListOf<String>()
                while (i < lines.size) {
                    val nextLine = lines[i].trimEnd()
                    if (nextLine.isBlank() || nextLine.startsWith("#") ||
                        nextLine.startsWith("---") || nextLine.startsWith("> ") ||
                        ORDERED_LIST_REGEX.matches(nextLine) ||
                        UNORDERED_LIST_REGEX.matches(nextLine) ||
                        TABLE_ROW_REGEX.matches(nextLine)
                    ) break
                    paragraphLines.add(nextLine)
                    i++
                }
                if (paragraphLines.isEmpty()) {
                    // Safety: unrecognised syntax — skip the line to avoid infinite loop
                    i++
                } else {
                    blocks.add(MdBlock.Paragraph(paragraphLines.joinToString(" ")))
                }
            }
        }
    }

    return blocks
}

/** Splits `| cell1 | cell2 | cell3 |` into `["cell1", "cell2", "cell3"]`. */
private fun parseTableCells(line: String): List<String> {
    return line.trim().removePrefix("|").removeSuffix("|")
        .split("|")
        .map { it.trim() }
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
