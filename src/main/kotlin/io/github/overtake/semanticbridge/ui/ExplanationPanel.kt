package io.github.overtake.semanticbridge.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.icons.AllIcons
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import org.json.JSONObject
import java.awt.BorderLayout
import java.awt.Color
import java.awt.datatransfer.StringSelection
import java.util.LinkedList
import javax.swing.JPanel
import javax.swing.SwingUtilities

@Suppress("DEPRECATION")
class ExplanationPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val browser = JBCefBrowser()
    private val statusLabel = JBLabel().apply {
        border = JBUI.Borders.empty(4, 8)
    }

    private val history = LinkedList<HistoryEntry>()
    private var rawMarkdown = StringBuilder()
    private var currentTarget: String? = null
    private var cancelCallback: (() -> Unit)? = null
    private var reAnalyzeCallback: (() -> Unit)? = null
    @Volatile private var pageReady = false
    private var pendingCalls = mutableListOf<String>()
    private lateinit var toolbar: ActionToolbar

    data class HistoryEntry(val targetName: String, val rawMarkdown: String)

    init {
        Disposer.register(this, browser)

        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(cefBrowser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                pageReady = true
                SwingUtilities.invokeLater {
                    for (js in pendingCalls) {
                        browser.cefBrowser.executeJavaScript(js, browser.cefBrowser.url ?: "about:blank", 0)
                    }
                    pendingCalls.clear()
                }
            }
        }, browser.cefBrowser)

        toolbar = createToolbar()
        add(toolbar.component, BorderLayout.NORTH)
        add(browser.component, BorderLayout.CENTER)
        add(statusLabel, BorderLayout.SOUTH)

        browser.loadHTML(buildPageHtml())
    }

    fun showEmpty() {
        cancelCallback = null
        statusLabel.text = ""
        val msg = "Right-click a Java class and select <b>Explain Architecture</b> to get started."
        executeJs("renderer.setContent('<p class=\"placeholder\">${escapeJsInline(msg)}</p>')")
        toolbar.updateActionsImmediately()
    }

    fun showLoading(targetName: String) {
        currentTarget = targetName
        rawMarkdown = StringBuilder()
        statusLabel.text = "Analyzing $targetName..."
        executeJs("renderer.clear()")
    }

    fun showError(message: String) {
        cancelCallback = null
        statusLabel.text = ""
        val escaped = escapeJsInline(
            message.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        )
        executeJs("renderer.setContent('<div class=\"error\"><b>Error</b><br/>$escaped</div>')")
        toolbar.updateActionsImmediately()
    }

    fun appendChunk(markdownChunk: String) {
        rawMarkdown.append(markdownChunk)
        executeJs("renderer.append(${JSONObject.quote(markdownChunk)})")
    }

    fun finishStreaming() {
        cancelCallback = null
        val target = currentTarget ?: "Unknown"
        statusLabel.text = "Done — $target"
        executeJs("renderer.finish()")
        addToHistory(HistoryEntry(target, rawMarkdown.toString()))
        toolbar.updateActionsImmediately()
    }

    fun setCancelCallback(callback: () -> Unit) { cancelCallback = callback }
    fun setReAnalyzeCallback(callback: () -> Unit) { reAnalyzeCallback = callback }

    private fun executeJs(js: String) {
        if (!pageReady) {
            pendingCalls.add(js)
            return
        }
        browser.cefBrowser.executeJavaScript(js, browser.cefBrowser.url ?: "about:blank", 0)
    }

    private fun addToHistory(entry: HistoryEntry) {
        history.removeIf { it.targetName == entry.targetName }
        history.addFirst(entry)
        while (history.size > MAX_HISTORY) {
            history.removeLast()
        }
    }

    private fun createToolbar(): ActionToolbar {
        val group = DefaultActionGroup().apply {
            add(StopAction())
            add(CopyAction())
            add(RefreshAction())
            addSeparator()
            add(HistoryAction())
        }
        return ActionManager.getInstance()
            .createActionToolbar("SemanticBridge.Toolbar", group, true)
            .apply { targetComponent = this@ExplanationPanel }
    }

    private inner class StopAction : AnAction("Stop", "Cancel current analysis", AllIcons.Actions.Suspend) {
        override fun actionPerformed(e: AnActionEvent) {
            cancelCallback?.invoke()
            cancelCallback = null
            executeJs("renderer.finish()")
            statusLabel.text = "Cancelled"
            toolbar.updateActionsImmediately()
        }
        override fun update(e: AnActionEvent) { e.presentation.isEnabled = cancelCallback != null }
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private inner class CopyAction : AnAction("Copy", "Copy result to clipboard", AllIcons.Actions.Copy) {
        override fun actionPerformed(e: AnActionEvent) {
            val text = rawMarkdown.toString()
            if (text.isNotBlank()) {
                CopyPasteManager.getInstance().setContents(StringSelection(text))
                statusLabel.text = "Copied to clipboard"
            }
        }
        override fun update(e: AnActionEvent) { e.presentation.isEnabled = rawMarkdown.isNotBlank() }
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private inner class RefreshAction : AnAction("Re-analyze", "Re-run analysis on last target", AllIcons.Actions.Refresh) {
        override fun actionPerformed(e: AnActionEvent) { reAnalyzeCallback?.invoke() }
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = currentTarget != null && cancelCallback == null && reAnalyzeCallback != null
        }
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private inner class HistoryAction : AnAction("History", "Show previous results", AllIcons.Vcs.History) {
        override fun actionPerformed(e: AnActionEvent) {
            if (history.isEmpty()) return
            val popupGroup = DefaultActionGroup()
            for (entry in history) {
                popupGroup.add(object : AnAction(entry.targetName) {
                    override fun actionPerformed(e: AnActionEvent) {
                        rawMarkdown = StringBuilder(entry.rawMarkdown)
                        currentTarget = entry.targetName
                        val html = JSONObject.quote(entry.rawMarkdown)
                        executeJs("renderer.restore($html)")
                        statusLabel.text = "Restored — ${entry.targetName}"
                    }
                    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
                })
            }
            val popup = ActionManager.getInstance()
                .createActionPopupMenu("SemanticBridge.History", popupGroup)
            val component = e.inputEvent?.component ?: this@ExplanationPanel
            popup.component.show(component, 0, component.height)
        }
        override fun update(e: AnActionEvent) { e.presentation.isEnabled = history.isNotEmpty() }
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private fun loadMarkedJs(): String {
        return ExplanationPanel::class.java.getResourceAsStream("/marked.min.js")
            ?.bufferedReader()?.readText() ?: ""
    }

    private fun buildPageHtml(): String {
        val bg = colorToHex(UIUtil.getPanelBackground())
        val fg = colorToHex(UIUtil.getLabelForeground())
        val dark = isDark(UIUtil.getPanelBackground())

        val codeBg = if (dark) "rgba(255,255,255,0.07)" else "rgba(0,0,0,0.05)"
        val codeColor = if (dark) "#e5c07b" else "#986801"
        val preBg = if (dark) "rgba(255,255,255,0.04)" else "rgba(0,0,0,0.03)"
        val border = if (dark) "rgba(255,255,255,0.08)" else "rgba(0,0,0,0.08)"
        val placeholder = if (dark) "#666" else "#999"
        val error = if (dark) "#f87171" else "#dc2626"
        val scrollThumb = if (dark) "rgba(255,255,255,0.15)" else "rgba(0,0,0,0.15)"
        val scrollThumbHover = if (dark) "rgba(255,255,255,0.3)" else "rgba(0,0,0,0.3)"
        val cursorColor = if (dark) "rgba(255,255,255,0.5)" else "rgba(0,0,0,0.4)"
        val dotColor = if (dark) "rgba(255,255,255,0.35)" else "rgba(0,0,0,0.25)"

        val markedJs = loadMarkedJs()

        return """
        <!DOCTYPE html>
        <html>
        <head><meta charset="utf-8">
        <style>
            * { margin: 0; padding: 0; box-sizing: border-box; }
            html { scroll-behavior: smooth; }

            ::-webkit-scrollbar { width: 6px; height: 6px; }
            ::-webkit-scrollbar-track { background: transparent; }
            ::-webkit-scrollbar-thumb { background: $scrollThumb; border-radius: 3px; }
            ::-webkit-scrollbar-thumb:hover { background: $scrollThumbHover; }

            body {
                font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', system-ui, Helvetica, Arial, sans-serif;
                font-size: 13px;
                line-height: 1.65;
                color: $fg;
                background: $bg;
                padding: 14px 18px;
                -webkit-font-smoothing: antialiased;
            }

            p { margin: 4px 0 10px; }

            h1 { font-size: 1.4em; font-weight: 600; margin: 20px 0 8px; padding-bottom: 5px;
                 border-bottom: 1px solid $border; }
            h2 { font-size: 1.2em; font-weight: 600; margin: 18px 0 6px; padding-bottom: 4px;
                 border-bottom: 1px solid $border; }
            h3 { font-size: 1.05em; font-weight: 600; margin: 14px 0 4px; }

            #content > :first-child { margin-top: 2px; }

            code {
                font-family: 'JetBrains Mono', Consolas, 'Courier New', monospace;
                background: $codeBg;
                color: $codeColor;
                padding: 2px 6px;
                border-radius: 4px;
                font-size: 0.9em;
            }

            pre {
                background: $preBg;
                border-radius: 6px;
                padding: 12px 14px;
                margin: 8px 0;
                overflow-x: auto;
            }

            pre code {
                background: none;
                color: inherit;
                padding: 0;
                border-radius: 0;
                font-size: 0.88em;
                line-height: 1.55;
            }

            ul, ol { padding-left: 22px; margin: 4px 0 8px; }
            li { margin: 3px 0; line-height: 1.6; }

            strong { font-weight: 600; }
            em { font-style: italic; }

            hr { border: none; border-top: 1px solid $border; margin: 14px 0; }

            .placeholder { color: $placeholder; text-align: center; margin-top: 40px; }
            .error { color: $error; margin: 16px 0; }

            @keyframes fadeIn {
                from { opacity: 0; transform: translateY(4px); }
                to { opacity: 1; transform: translateY(0); }
            }
            .fade-in {
                animation: fadeIn 180ms ease-out forwards;
            }

            @keyframes blink {
                0%, 100% { opacity: 1; }
                50% { opacity: 0; }
            }
            #cursor {
                display: inline-block;
                width: 2px;
                height: 1em;
                background: $cursorColor;
                margin-left: 2px;
                vertical-align: text-bottom;
                animation: blink 0.8s step-end infinite;
            }

            @keyframes pulse {
                0%, 80%, 100% { opacity: 0.2; transform: scale(0.8); }
                40% { opacity: 1; transform: scale(1); }
            }
            .loading-dots {
                display: flex;
                gap: 6px;
                padding: 20px 0;
                justify-content: center;
            }
            .loading-dots span {
                width: 6px;
                height: 6px;
                border-radius: 50%;
                background: $dotColor;
                display: inline-block;
                animation: pulse 1.4s ease-in-out infinite;
            }
            .loading-dots span:nth-child(2) { animation-delay: 0.2s; }
            .loading-dots span:nth-child(3) { animation-delay: 0.4s; }
        </style>
        <script>$markedJs</script>
        <script>
        (function() {
            var buffer = '';
            var rendering = false;
            var streaming = false;
            var prevBlockCount = 0;
            var autoScroll = true;

            var content = null;

            function getContent() {
                if (!content) content = document.getElementById('content');
                return content;
            }

            function scheduleRender() {
                if (rendering) return;
                rendering = true;
                requestAnimationFrame(doRender);
            }

            function doRender() {
                rendering = false;
                var el = getContent();
                if (!el) return;

                var html = marked.parse(buffer);
                var tmp = document.createElement('div');
                tmp.innerHTML = html;
                var newChildren = Array.from(tmp.children);
                var oldChildren = Array.from(el.children);

                // Remove cursor if present
                var cur = document.getElementById('cursor');
                if (cur) cur.remove();

                var oldCount = el.querySelectorAll(':scope > :not(#cursor):not(.loading-dots)').length;

                // Replace content only if structure changed
                el.innerHTML = html;

                // Animate genuinely new block elements
                var allBlocks = el.children;
                for (var i = oldCount; i < allBlocks.length; i++) {
                    allBlocks[i].classList.add('fade-in');
                }

                // Append cursor during streaming
                if (streaming) {
                    var cursor = document.createElement('span');
                    cursor.id = 'cursor';
                    var last = el.lastElementChild;
                    if (last) {
                        last.appendChild(cursor);
                    } else {
                        el.appendChild(cursor);
                    }
                }

                if (autoScroll) {
                    window.scrollTo({ top: document.body.scrollHeight, behavior: 'smooth' });
                }
            }

            window.addEventListener('scroll', function() {
                var atBottom = (window.innerHeight + window.scrollY) >= (document.body.scrollHeight - 30);
                autoScroll = atBottom;
            });

            window.renderer = {
                clear: function() {
                    buffer = '';
                    streaming = true;
                    autoScroll = true;
                    prevBlockCount = 0;
                    var el = getContent();
                    if (el) el.innerHTML = '<div class="loading-dots"><span></span><span></span><span></span></div>';
                },
                append: function(text) {
                    // Remove loading dots on first chunk
                    if (streaming && buffer.length === 0) {
                        var dots = document.querySelector('.loading-dots');
                        if (dots) dots.remove();
                    }
                    buffer += text;
                    scheduleRender();
                },
                finish: function() {
                    streaming = false;
                    doRender();
                    var cur = document.getElementById('cursor');
                    if (cur) cur.remove();
                },
                restore: function(markdown) {
                    buffer = markdown;
                    streaming = false;
                    autoScroll = false;
                    prevBlockCount = 0;
                    var el = getContent();
                    if (el) el.innerHTML = marked.parse(markdown);
                },
                setContent: function(html) {
                    buffer = '';
                    streaming = false;
                    prevBlockCount = 0;
                    var el = getContent();
                    if (el) el.innerHTML = html;
                }
            };
        })();
        </script>
        </head>
        <body>
            <div id="content">
                <p class="placeholder">Right-click a Java class and select <b>Explain Architecture</b> to get started.</p>
            </div>
        </body>
        </html>
        """.trimIndent()
    }

    private fun isDark(color: Color): Boolean {
        val luminance = (0.299 * color.red + 0.587 * color.green + 0.114 * color.blue) / 255.0
        return luminance < 0.5
    }

    private fun colorToHex(color: Color): String =
        String.format("#%02x%02x%02x", color.red, color.green, color.blue)

    private fun escapeJsInline(s: String): String =
        s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")

    override fun dispose() {
        cancelCallback = null
        reAnalyzeCallback = null
        history.clear()
    }

    companion object {
        private const val MAX_HISTORY = 10
    }
}
