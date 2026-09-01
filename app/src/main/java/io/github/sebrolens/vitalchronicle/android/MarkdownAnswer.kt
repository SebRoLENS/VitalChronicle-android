package io.github.sebrolens.vitalchronicle.android

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

private val headingPattern = Regex("""^(#{1,3})\s+(.+)$""")
private val unorderedListPattern = Regex("""^\s*[-*•]\s+(.+)$""")
private val orderedListPattern = Regex("""^\s*(\d+)[.)]\s+(.+)$""")
private val horizontalRulePattern = Regex("""^\s*(---+|\*\*\*+)\s*$""")
private val backtick = 96.toChar()
private val inlineMarkdownPattern = Regex(
    """(\*\*\*.+?\*\*\*|\*\*.+?\*\*|__.+?__|\*.+?\*)""" +
        "|" + Regex.escape(backtick.toString()) + ".+?" + Regex.escape(backtick.toString())
)

@Composable
fun MarkdownAnswer(markdown: String, modifier: Modifier = Modifier) {
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (rawLine in markdown.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            val heading = headingPattern.matchEntire(line)
            val unordered = unorderedListPattern.matchEntire(line)
            val ordered = orderedListPattern.matchEntire(line)

            when {
                horizontalRulePattern.matches(line) -> HorizontalDivider()

                heading != null -> {
                    val style = when (heading.groupValues[1].length) {
                        1 -> MaterialTheme.typography.headlineSmall
                        2 -> MaterialTheme.typography.titleLarge
                        else -> MaterialTheme.typography.titleMedium
                    }
                    Text(
                        text = markdownInline(heading.groupValues[2], codeBackground),
                        style = style,
                        fontWeight = FontWeight.Bold,
                    )
                }

                unordered != null -> MarkdownBullet(
                    marker = "•",
                    text = unordered.groupValues[1],
                    codeBackground = codeBackground,
                )

                ordered != null -> MarkdownBullet(
                    marker = ordered.groupValues[1] + ".",
                    text = ordered.groupValues[2],
                    codeBackground = codeBackground,
                )

                line.startsWith(">") -> {
                    Text(
                        text = markdownInline(line.removePrefix(">").trimStart(), codeBackground),
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(8.dp),
                            )
                            .padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> Text(
                    text = markdownInline(line, codeBackground),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun MarkdownBullet(marker: String, text: String, codeBackground: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = marker,
            modifier = Modifier.width(28.dp),
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = markdownInline(text, codeBackground),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private fun markdownInline(text: String, codeBackground: Color): AnnotatedString =
    buildAnnotatedString {
        var cursor = 0
        for (match in inlineMarkdownPattern.findAll(text)) {
            if (match.range.first > cursor) {
                append(text.substring(cursor, match.range.first))
            }

            val token = match.value
            when {
                token.firstOrNull() == backtick && token.lastOrNull() == backtick -> {
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = codeBackground,
                        )
                    ) {
                        append(token.substring(1, token.length - 1))
                    }
                }

                token.startsWith("***") && token.endsWith("***") -> {
                    withStyle(
                        SpanStyle(
                            fontWeight = FontWeight.Bold,
                            fontStyle = FontStyle.Italic,
                        )
                    ) {
                        append(token.substring(3, token.length - 3))
                    }
                }

                (token.startsWith("**") && token.endsWith("**")) ||
                    (token.startsWith("__") && token.endsWith("__")) -> {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(token.substring(2, token.length - 2))
                    }
                }

                token.startsWith("*") && token.endsWith("*") -> {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(token.substring(1, token.length - 1))
                    }
                }

                else -> append(token)
            }
            cursor = match.range.last + 1
        }
        if (cursor < text.length) append(text.substring(cursor))
    }
