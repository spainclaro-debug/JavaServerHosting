package dev.javahost.core.launch

/**
 * Splits a free-text argument string the same way a POSIX shell would split a
 * command line, but without performing any expansion (no globbing, no `$VAR`,
 * no command substitution).  The result is only ever used as a `List<String>`
 * handed to [ProcessBuilder]; no shell is involved anywhere in the launcher.
 *
 * Rules implemented here:
 *  - unquoted whitespace separates arguments,
 *  - `'...'` keeps everything literally (even backslashes),
 *  - `"..."` keeps whitespace, honours `\"`, `\\`, `\$`, `` \` `` and `\`-newline,
 *  - outside quotes `\x` yields `x` literally (dropping the backslash),
 *  - an unmatched trailing quote is tolerated: the partial token is kept.
 *
 * The tokenizer is deliberately forgiving: a malformed JVM-args string typed by
 * a user should never make the app crash, it should just produce the most
 * sensible list of tokens.
 */
object ArgTokenizer {

    /** Tokenizes [raw]; returns an empty list for blank input. */
    fun tokenize(raw: String?): List<String> {
        if (raw.isNullOrEmpty()) return emptyList()
        val tokens = ArrayList<String>()
        val current = StringBuilder()
        var tokenStarted = false
        var inSingle = false
        var inDouble = false
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            when {
                inSingle -> {
                    if (c == '\'') inSingle = false else current.append(c)
                }
                inDouble -> when (c) {
                    '"' -> inDouble = false
                    '\\' -> {
                        val next = raw.getOrNull(i + 1)
                        if (next == null) {
                            // Trailing backslash: keep it, like bash does in a
                            // still-open double-quoted string.
                            current.append('\\')
                        } else if (next == '\n') {
                            // Line continuation inside quotes: drop both.
                            i++
                        } else if (next == '"' || next == '\\' || next == '$' || next == '`') {
                            current.append(next)
                            i++
                        } else {
                            // Backslash is literal before other characters.
                            current.append('\\')
                            current.append(next)
                            i++
                        }
                    }
                    else -> current.append(c)
                }
                c == '\'' -> {
                    inSingle = true
                    tokenStarted = true
                }
                c == '"' -> {
                    inDouble = true
                    tokenStarted = true
                }
                c == '\\' -> {
                    tokenStarted = true
                    val next = raw.getOrNull(i + 1)
                    when {
                        next == null -> Unit
                        next == '\n' -> i++ // line continuation
                        next == '\r' && raw.getOrNull(i + 2) == '\n' -> i += 2
                        else -> {
                            current.append(next)
                            i++
                        }
                    }
                }
                c.isWhitespace() -> {
                    if (tokenStarted) {
                        tokens.add(current.toString())
                        current.setLength(0)
                        tokenStarted = false
                    }
                }
                else -> {
                    current.append(c)
                    tokenStarted = true
                }
            }
            i++
        }
        if (tokenStarted) tokens.add(current.toString())
        return tokens
    }
}
