package io.github.overtake.semanticbridge.ui

object MarkdownRenderer {

    private val NUMBERED_LIST_RE = Regex("""^\d+\.\s+(.*)""")
    private val HR_RE = Regex("""^-{3,}$""")

    fun toHtml(markdown: String): String {
        if (markdown.isBlank()) return ""
        val lines = markdown.lines()
        val sb = StringBuilder()
        var inCodeBlock = false
        var inList = false
        var listTag = ""
        val paraBuffer = mutableListOf<String>()

        fun flushParagraph() {
            if (paraBuffer.isNotEmpty()) {
                sb.append("<p>").append(paraBuffer.joinToString(" ") { renderInline(it) }).append("</p>")
                paraBuffer.clear()
            }
        }

        fun closeList() {
            if (inList) {
                sb.append("</").append(listTag).append(">")
                inList = false
            }
        }

        fun openList(tag: String) {
            if (!inList || listTag != tag) {
                closeList()
                sb.append("<").append(tag).append(">")
                inList = true
                listTag = tag
            }
        }

        for (line in lines) {
            when {
                line.startsWith("```") -> {
                    flushParagraph()
                    closeList()
                    if (inCodeBlock) {
                        sb.append("</code></pre>")
                        inCodeBlock = false
                    } else {
                        sb.append("<pre><code>")
                        inCodeBlock = true
                    }
                }
                inCodeBlock -> {
                    sb.append(escapeHtml(line)).append("\n")
                }
                line.startsWith("### ") -> {
                    flushParagraph(); closeList()
                    sb.append("<h3>").append(renderInline(line.removePrefix("### "))).append("</h3>")
                }
                line.startsWith("## ") -> {
                    flushParagraph(); closeList()
                    sb.append("<h2>").append(renderInline(line.removePrefix("## "))).append("</h2>")
                }
                line.startsWith("# ") -> {
                    flushParagraph(); closeList()
                    sb.append("<h1>").append(renderInline(line.removePrefix("# "))).append("</h1>")
                }
                line.startsWith("- ") || line.startsWith("* ") -> {
                    flushParagraph()
                    openList("ul")
                    sb.append("<li>").append(renderInline(line.drop(2))).append("</li>")
                }
                NUMBERED_LIST_RE.matches(line) -> {
                    flushParagraph()
                    openList("ol")
                    val content = NUMBERED_LIST_RE.find(line)!!.groupValues[1]
                    sb.append("<li>").append(renderInline(content)).append("</li>")
                }
                HR_RE.matches(line) -> {
                    flushParagraph(); closeList()
                    sb.append("<hr>")
                }
                line.isBlank() -> {
                    flushParagraph(); closeList()
                }
                else -> {
                    if (inList) closeList()
                    paraBuffer.add(line)
                }
            }
        }

        flushParagraph()
        closeList()
        if (inCodeBlock) sb.append("</code></pre>")

        return sb.toString()
    }

    private fun renderInline(text: String): String {
        var result = escapeHtml(text)
        result = Regex("""\*\*(.+?)\*\*""").replace(result) { "<strong>${it.groupValues[1]}</strong>" }
        result = Regex("""\*(.+?)\*""").replace(result) { "<em>${it.groupValues[1]}</em>" }
        result = Regex("""`(.+?)`""").replace(result) { "<code>${it.groupValues[1]}</code>" }
        return result
    }

    private fun escapeHtml(text: String): String =
        text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
}
