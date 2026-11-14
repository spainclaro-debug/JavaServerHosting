package dev.javahost.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.javahost.core.model.ConsoleEntry
import dev.javahost.core.model.LogLevel
import dev.javahost.ui.theme.Danger
import dev.javahost.ui.theme.TextMuted
import dev.javahost.ui.theme.WarnAmber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/* -------------------------------------------------------------------------- */
/* Terminal palette                                                            */
/* -------------------------------------------------------------------------- */

/** Near-black terminal background shared by the console screen. */
val TerminalBackground = Color(0xFF060B0E)

/** Default (INFO) line colour on the terminal background. */
val TerminalText = Color(0xFFD3DEE3)

/** Timestamps / separators, deliberately low contrast. */
val TerminalTimestamp = Color(0xFF5D707A)

/** Command echoes typed into the server console. */
val TerminalCommand = Color(0xFF4DD0E1)

private val CONSOLE_TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.US)

/** Per-level colour used by [ConsoleLineRow]; keeps the colour policy in one place. */
fun consoleLevelColor(level: LogLevel): Color = when (level) {
    LogLevel.INFO -> TerminalText
    LogLevel.COMMAND -> TerminalCommand
    LogLevel.WARN -> WarnAmber
    LogLevel.ERROR -> Danger
    LogLevel.DEBUG, LogLevel.TRACE -> TextMuted
    LogLevel.UNKNOWN -> TerminalText
}

/** Short level tag used when timestamps are hidden (e.g. "WARN", "CMD"). */
fun consoleLevelTag(level: LogLevel): String = when (level) {
    LogLevel.TRACE -> "TRACE"
    LogLevel.DEBUG -> "DEBUG"
    LogLevel.INFO -> "INFO"
    LogLevel.WARN -> "WARN"
    LogLevel.ERROR -> "ERROR"
    LogLevel.COMMAND -> "CMD"
    LogLevel.UNKNOWN -> "LOG"
}

/** Non-blank, human-readable description of a failed [Result]. */
fun Throwable.readableMessage(): String =
    message?.trim()?.takeIf { it.isNotEmpty() } ?: this::class.simpleName ?: "Unknown error"

/* -------------------------------------------------------------------------- */
/* Rows & filters                                                              */
/* -------------------------------------------------------------------------- */

/**
 * One console line rendered in a monospace terminal style. Timestamps are
 * optional because narrow phones and long stack traces both benefit from the
 * extra horizontal room.
 */
@Composable
fun ConsoleLineRow(entry: ConsoleEntry, showTimestamp: Boolean) {
    val color = consoleLevelColor(entry.level)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp),
        verticalAlignment = Alignment.Top
    ) {
        if (showTimestamp) {
            Text(
                text = CONSOLE_TIME_FORMAT.format(Date(entry.timestamp)),
                color = TerminalTimestamp,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                lineHeight = 14.sp,
                modifier = Modifier.width(56.dp)
            )
        }
        Text(
            text = entry.text,
            color = color,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.5.sp,
            lineHeight = 15.sp,
            modifier = Modifier.weight(1f)
        )
    }
}

private data class ConsoleFilterOption(val level: LogLevel?, val label: String)

private val CONSOLE_FILTERS = listOf(
    ConsoleFilterOption(null, "All"),
    ConsoleFilterOption(LogLevel.INFO, "Info"),
    ConsoleFilterOption(LogLevel.WARN, "Warn"),
    ConsoleFilterOption(LogLevel.ERROR, "Error"),
    ConsoleFilterOption(LogLevel.DEBUG, "Debug")
)

/** Horizontally scrollable level chips; null means "All". */
@Composable
fun ConsoleLevelFilter(selected: LogLevel?, onSelect: (LogLevel?) -> Unit) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CONSOLE_FILTERS.forEach { option ->
            FilterChip(
                selected = selected == option.level,
                onClick = { onSelect(option.level) },
                label = { Text(option.label, fontSize = 11.sp) }
            )
        }
    }
}

/** Centered terminal-styled message used instead of a blank console. */
@Composable
fun ConsoleEmptyState(title: String, body: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = ">_",
            color = TerminalTimestamp,
            fontFamily = FontFamily.Monospace,
            fontSize = 20.sp
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = title,
            color = TerminalText,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = body,
            color = TerminalTimestamp,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            textAlign = TextAlign.Center
        )
    }
}
