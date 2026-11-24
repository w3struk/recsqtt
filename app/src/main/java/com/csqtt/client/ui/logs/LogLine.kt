package com.csqtt.client.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.csqtt.client.LogEntry
import com.csqtt.client.LogLevel
import com.csqtt.client.LogTimestampPolicy
import com.csqtt.client.ui.design.CsqttShapes
import com.csqtt.client.ui.design.CsqttSpacing
import com.csqtt.client.ui.design.CsqttTheme

private data class TagColors(
    val background: Color,
    val text: Color,
    val border: Color = Color.Transparent,
)

private enum class LogTagType {
    OK, NET, ERR, LOG, DBG
}

private fun resolveTagType(tag: String, level: LogLevel, isError: Boolean): LogTagType {
    if (isError || level == LogLevel.ERR ||
        tag.contains("ERR", ignoreCase = true) ||
        tag.contains("ОШИБКА", ignoreCase = true) ||
        tag.contains("ФАТАЛ", ignoreCase = true)
    ) {
        return LogTagType.ERR
    }
    if (level == LogLevel.NET ||
        tag.equals("NET", ignoreCase = true) ||
        tag.contains("СЕТЬ", ignoreCase = true)
    ) {
        return LogTagType.NET
    }
    if (level == LogLevel.OK ||
        tag.equals("OK", ignoreCase = true) ||
        tag.contains("УСПЕХ", ignoreCase = true) ||
        tag.contains("ВОРКЕР", ignoreCase = true) ||
        tag.contains("WRAP", ignoreCase = true)
    ) {
        return LogTagType.OK
    }
    if (level == LogLevel.DBG ||
        tag.equals("DBG", ignoreCase = true) ||
        tag.equals("DEBUG", ignoreCase = true)
    ) {
        return LogTagType.DBG
    }
    return LogTagType.LOG
}

private fun resolveTagColors(tagType: LogTagType, isDark: Boolean): TagColors {
    return if (isDark) {
        when (tagType) {
            LogTagType.OK -> TagColors(
                background = Color(0xFF052E16),
                text = Color(0xFF4ADE80),
                border = Color(0xFF14532D),
            )
            LogTagType.NET -> TagColors(
                background = Color(0xFF0C2340),
                text = Color(0xFF38BDF8),
                border = Color(0xFF0369A1),
            )
            LogTagType.ERR -> TagColors(
                background = Color(0xFF3E1218),
                text = Color(0xFFFF897D),
                border = Color(0xFF681D26),
            )
            LogTagType.LOG, LogTagType.DBG -> TagColors(
                background = Color(0xFF1E293B),
                text = Color(0xFF94A3B8),
                border = Color(0xFF334155),
            )
        }
    } else {
        when (tagType) {
            LogTagType.OK -> TagColors(
                background = Color(0xFFDCFCE7),
                text = Color(0xFF15803D),
                border = Color(0xFF86EFAC),
            )
            LogTagType.NET -> TagColors(
                background = Color(0xFFE0F2FE),
                text = Color(0xFF0369A1),
                border = Color(0xFF7DD3FC),
            )
            LogTagType.ERR -> TagColors(
                background = Color(0xFFFEE2E2),
                text = Color(0xFFB91C1C),
                border = Color(0xFFFCA5A5),
            )
            LogTagType.LOG, LogTagType.DBG -> TagColors(
                background = Color(0xFFF1F5F9),
                text = Color(0xFF334155),
                border = Color(0xFFCBD5E1),
            )
        }
    }
}

private fun parseMessageTag(rawMessage: String, level: LogLevel): Pair<String, String> {
    val trimmed = rawMessage.trim()
    if (trimmed.startsWith("[")) {
        val closeIndex = trimmed.indexOf(']')
        if (closeIndex in 1..24) {
            val tag = trimmed.substring(1, closeIndex).trim()
            val body = trimmed.substring(closeIndex + 1).trim()
            if (tag.isNotEmpty() && body.isNotEmpty()) {
                return tag to body
            }
        }
    }
    return level.name to trimmed
}

private fun parseAnsiLogBody(
    text: String,
    defaultColor: Color,
    greenColor: Color,
    redColor: Color,
): AnnotatedString {
    val ansiRegex = Regex("\u001B\\[([0-9;]+)m")
    if (!text.contains("\u001B[")) {
        return AnnotatedString(text)
    }
    val builder = AnnotatedString.Builder()
    var currentIndex = 0
    var currentColor = defaultColor

    for (match in ansiRegex.findAll(text)) {
        if (match.range.first > currentIndex) {
            val segment = text.substring(currentIndex, match.range.first)
            builder.pushStyle(SpanStyle(color = currentColor))
            builder.append(segment)
            builder.pop()
        }
        val code = match.groupValues[1]
        currentColor = when (code) {
            "32", "92" -> greenColor
            "31", "91" -> redColor
            "0" -> defaultColor
            else -> currentColor
        }
        currentIndex = match.range.last + 1
    }
    if (currentIndex < text.length) {
        val segment = text.substring(currentIndex)
        builder.pushStyle(SpanStyle(color = currentColor))
        builder.append(segment)
        builder.pop()
    }
    return builder.toAnnotatedString()
}

@Composable
internal fun LogLine(
    entry: LogEntry,
    modifier: Modifier = Modifier,
) {
    val terminalColors = CsqttTheme.extendedColors
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f

    val level = entry.level ?: when {
        entry.isError -> LogLevel.ERR
        entry.priority <= 2 -> LogLevel.OK
        entry.priority <= 4 -> LogLevel.DBG
        entry.priority <= 6 -> LogLevel.NET
        else -> LogLevel.LOG
    }

    val (rawTag, body) = parseMessageTag(entry.message, level)
    val tagType = resolveTagType(rawTag, level, entry.isError)

    val displayTag = when (tagType) {
        LogTagType.OK -> "OK"
        LogTagType.NET -> if (rawTag.equals("СЕТЬ", ignoreCase = true)) "NET" else rawTag
        LogTagType.ERR -> if (rawTag.equals("ОШИБКА", ignoreCase = true) || rawTag.equals("ФАТАЛ", ignoreCase = true) || rawTag.equals("ERROR", ignoreCase = true)) "ERR" else rawTag
        LogTagType.DBG -> if (rawTag.equals("DEBUG", ignoreCase = true)) "DBG" else rawTag
        LogTagType.LOG -> rawTag
    }
    val tagColors = resolveTagColors(tagType = tagType, isDark = isDark)

    val bodyColor = when (tagType) {
        LogTagType.ERR -> terminalColors.terminalError
        LogTagType.OK -> terminalColors.terminalSuccess
        else -> terminalColors.terminalText
    }

    val annotatedBody = remember(body, bodyColor, terminalColors.terminalSuccess, terminalColors.terminalError) {
        parseAnsiLogBody(
            text = body,
            defaultColor = bodyColor,
            greenColor = terminalColors.terminalSuccess,
            redColor = terminalColors.terminalError,
        )
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(CsqttSpacing.Sm),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = LogTimestampPolicy.format(entry.timestampMillis),
            color = terminalColors.terminalMuted,
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = 10.sp,
                fontWeight = FontWeight.Normal,
                lineHeight = 16.sp,
            ),
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.width(50.dp),
        )

        Surface(
            shape = CsqttShapes.Pill,
            color = tagColors.background,
            contentColor = tagColors.text,
            border = BorderStroke(1.dp, tagColors.border),
            modifier = Modifier.defaultMinSize(minHeight = 20.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
            ) {
                Text(
                    text = displayTag,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.4.sp,
                    ),
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                )
            }
        }

        Text(
            text = annotatedBody,
            color = bodyColor,
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
            ),
            fontFamily = FontFamily.Monospace,
            fontWeight = if (tagType == LogTagType.ERR) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )

        if (entry.count > 1) {
            val countColors = if (isDark) {
                TagColors(
                    background = Color(0xFF0C2340),
                    text = Color(0xFF38BDF8),
                    border = Color(0xFF0369A1),
                )
            } else {
                TagColors(
                    background = Color(0xFFE0F2FE),
                    text = Color(0xFF0369A1),
                    border = Color(0xFF7DD3FC),
                )
            }
            Surface(
                shape = CsqttShapes.Pill,
                color = countColors.background,
                contentColor = countColors.text,
                border = BorderStroke(1.dp, countColors.border),
                modifier = Modifier.defaultMinSize(minHeight = 18.dp),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                ) {
                    Text(
                        text = "x${entry.count}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
