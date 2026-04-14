package io.github.overtake.semanticbridge.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownRendererTest {

    @Test
    fun `renders h1 heading`() {
        val html = MarkdownRenderer.toHtml("# Hello World")
        assertEquals("<h1>Hello World</h1>", html)
    }

    @Test
    fun `renders h2 heading`() {
        val html = MarkdownRenderer.toHtml("## Section Title")
        assertEquals("<h2>Section Title</h2>", html)
    }

    @Test
    fun `renders h3 heading`() {
        val html = MarkdownRenderer.toHtml("### Subsection")
        assertEquals("<h3>Subsection</h3>", html)
    }

    @Test
    fun `renders bold text`() {
        val html = MarkdownRenderer.toHtml("This is **bold** text")
        assertEquals("<p>This is <strong>bold</strong> text</p>", html)
    }

    @Test
    fun `renders italic text`() {
        val html = MarkdownRenderer.toHtml("This is *italic* text")
        assertEquals("<p>This is <em>italic</em> text</p>", html)
    }

    @Test
    fun `renders inline code`() {
        val html = MarkdownRenderer.toHtml("Use `MyClass` here")
        assertEquals("<p>Use <code>MyClass</code> here</p>", html)
    }

    @Test
    fun `renders bullet list`() {
        val html = MarkdownRenderer.toHtml("- first\n- second\n- third")
        assertEquals("<ul><li>first</li><li>second</li><li>third</li></ul>", html)
    }

    @Test
    fun `renders asterisk bullet list`() {
        val html = MarkdownRenderer.toHtml("* first\n* second")
        assertEquals("<ul><li>first</li><li>second</li></ul>", html)
    }

    @Test
    fun `renders numbered list`() {
        val html = MarkdownRenderer.toHtml("1. first\n2. second\n3. third")
        assertEquals("<ol><li>first</li><li>second</li><li>third</li></ol>", html)
    }

    @Test
    fun `renders code block`() {
        val html = MarkdownRenderer.toHtml("```kotlin\nval x = 1\n```")
        assertEquals("<pre><code>val x = 1\n</code></pre>", html)
    }

    @Test
    fun `blank line separates paragraphs`() {
        val html = MarkdownRenderer.toHtml("line one\n\nline two")
        assertEquals("<p>line one</p><p>line two</p>", html)
    }

    @Test
    fun `consecutive lines merge into single paragraph`() {
        val html = MarkdownRenderer.toHtml("line one\nline two\nline three")
        assertEquals("<p>line one line two line three</p>", html)
    }

    @Test
    fun `renders horizontal rule`() {
        val html = MarkdownRenderer.toHtml("above\n\n---\n\nbelow")
        assertEquals("<p>above</p><hr><p>below</p>", html)
    }

    @Test
    fun `escapes HTML entities in text`() {
        val html = MarkdownRenderer.toHtml("List<String> & Map<K, V>")
        assertTrue(html.contains("&lt;String&gt;"))
        assertTrue(html.contains("&amp;"))
    }

    @Test
    fun `mixed content renders correctly`() {
        val md = """
            # Title
            
            Some **bold** intro with `code`.
            
            ## Methods
            
            - `getUser()` returns a User
            - `deleteUser()` removes it
        """.trimIndent()

        val html = MarkdownRenderer.toHtml(md)
        assertTrue(html.contains("<h1>Title</h1>"))
        assertTrue(html.contains("<h2>Methods</h2>"))
        assertTrue(html.contains("<strong>bold</strong>"))
        assertTrue(html.contains("<li><code>getUser()</code> returns a User</li>"))
    }

    @Test
    fun `empty input returns empty string`() {
        assertEquals("", MarkdownRenderer.toHtml(""))
    }

    @Test
    fun `list ends before next heading`() {
        val html = MarkdownRenderer.toHtml("- item\n## Next")
        assertTrue(html.contains("</ul><h2>Next</h2>"))
    }

    @Test
    fun `inline code in headings`() {
        val html = MarkdownRenderer.toHtml("## The `UserService` class")
        assertEquals("<h2>The <code>UserService</code> class</h2>", html)
    }

    @Test
    fun `numbered list transitions to bullet list`() {
        val html = MarkdownRenderer.toHtml("1. first\n2. second\n\n- bullet one\n- bullet two")
        assertTrue(html.contains("<ol><li>first</li><li>second</li></ol>"))
        assertTrue(html.contains("<ul><li>bullet one</li><li>bullet two</li></ul>"))
    }
}
