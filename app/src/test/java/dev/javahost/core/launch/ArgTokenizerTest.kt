package dev.javahost.core.launch

import org.junit.Assert.assertEquals
import org.junit.Test

class ArgTokenizerTest {

    @Test
    fun `empty and blank input yield no tokens`() {
        assertEquals(emptyList<String>(), ArgTokenizer.tokenize(null))
        assertEquals(emptyList<String>(), ArgTokenizer.tokenize(""))
        assertEquals(emptyList<String>(), ArgTokenizer.tokenize("   \t  "))
    }

    @Test
    fun `splits on whitespace`() {
        assertEquals(
            listOf("-Xms512M", "-Xmx2G", "nogui"),
            ArgTokenizer.tokenize("-Xms512M   -Xmx2G\tnogui")
        )
    }

    @Test
    fun `double quotes keep internal spaces`() {
        assertEquals(
            listOf("-Dfoo=a b", "-Dbar=c"),
            ArgTokenizer.tokenize("-Dfoo=\"a b\" -Dbar=c")
        )
    }

    @Test
    fun `single quotes keep internal spaces and backslashes`() {
        assertEquals(
            listOf("-Dpath=C:\\my dir"),
            ArgTokenizer.tokenize("-Dpath='C:\\my dir'")
        )
    }

    @Test
    fun `escaped space outside quotes`() {
        assertEquals(listOf("a b", "c"), ArgTokenizer.tokenize("a\\ b c"))
    }

    @Test
    fun `escaped quote inside double quotes`() {
        assertEquals(
            listOf("say", "\"hi\""),
            ArgTokenizer.tokenize("say \\\"hi\\\"")
        )
    }

    @Test
    fun `empty quoted token is preserved`() {
        assertEquals(listOf("", "x"), ArgTokenizer.tokenize("\"\" x"))
    }

    @Test
    fun `unterminated quote keeps the partial token`() {
        assertEquals(listOf("abc"), ArgTokenizer.tokenize("\"abc"))
    }

    @Test
    fun `backslash newline is a line continuation`() {
        // Backslash-newline is removed, joining the surrounding text.
        assertEquals(listOf("ab"), ArgTokenizer.tokenize("a\\\nb"))
        // With a space before it, it separates the two words.
        assertEquals(listOf("a", "b"), ArgTokenizer.tokenize("a \\\nb"))
    }

    @Test
    fun `jvm style system properties survive`() {
        assertEquals(
            listOf("-XX:+UseG1GC", "-Dfoo.bar=baz", "-Dquoted=hello world"),
            ArgTokenizer.tokenize("-XX:+UseG1GC -Dfoo.bar=baz -Dquoted=\"hello world\"")
        )
    }
}
