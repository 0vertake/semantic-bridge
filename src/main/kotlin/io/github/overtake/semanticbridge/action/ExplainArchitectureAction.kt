package io.github.overtake.semanticbridge.action

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import io.github.overtake.semanticbridge.collector.ClassContextCollector
import io.github.overtake.semanticbridge.llm.LlmClient
import io.github.overtake.semanticbridge.llm.LlmException
import io.github.overtake.semanticbridge.llm.PromptBuilder
import io.github.overtake.semanticbridge.settings.PluginSettings
import io.github.overtake.semanticbridge.ui.ExplanationPanel

class ExplainArchitectureAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val psiClass = resolveTargetClass(e) ?: return

        val qualifiedName = psiClass.qualifiedName ?: return
        val displayName = psiClass.name ?: qualifiedName.substringAfterLast('.')

        val settings = PluginSettings.getInstance()
        val provider = settings.selectedModel.provider
        if (settings.activeApiKey.isBlank()) {
            showNotification(
                project,
                "${provider.displayName} API key not configured",
                "Go to Settings > Tools > Semantic Bridge to set your ${provider.displayName} API key.",
                NotificationType.WARNING,
            )
            return
        }

        val panel = activateToolWindow(project) ?: return
        panel.showLoading(displayName)
        panel.setReAnalyzeCallback { actionPerformed(e) }

        val task = object : Task.Backgroundable(project, "Analyzing architecture of $displayName", true) {
            @Volatile
            private var cancelled = false

            init {
                panel.setCancelCallback { cancelled = true }
            }

            override fun run(indicator: ProgressIndicator) {
                try {
                    val collector = ClassContextCollector(project)
                    val context = collector.collect(psiClass, indicator)

                    if (cancelled) return

                    indicator.text = "Generating explanation..."
                    val userPrompt = PromptBuilder.buildUserPrompt(context)

                    val client = LlmClient()
                    client.stream(
                        systemPrompt = PromptBuilder.systemPrompt,
                        userPrompt = userPrompt,
                        isCancelled = { cancelled || indicator.isCanceled },
                        onChunk = { chunk ->
                            ApplicationManager.getApplication().invokeLater {
                                panel.appendChunk(chunk)
                            }
                        },
                    )

                    if (!cancelled && !indicator.isCanceled) {
                        ApplicationManager.getApplication().invokeLater {
                            panel.finishStreaming()
                        }
                    }
                } catch (ex: LlmException) {
                    ApplicationManager.getApplication().invokeLater {
                        panel.showError(ex.message ?: "Unknown error")
                    }
                } catch (ex: Exception) {
                    ApplicationManager.getApplication().invokeLater {
                        panel.showError("Analysis failed: ${ex.message}")
                    }
                }
            }

            override fun onCancel() {
                cancelled = true
            }
        }

        task.queue()
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val psiClass = resolveTargetClass(e)

        e.presentation.isEnabledAndVisible = project != null
            && psiClass != null
            && !DumbService.isDumb(project)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    private fun resolveTargetClass(e: AnActionEvent): PsiClass? {
        val psiElement: PsiElement? = e.getData(CommonDataKeys.PSI_ELEMENT)

        if (psiElement is PsiClass) return psiElement

        if (psiElement != null) {
            val parent = PsiTreeUtil.getParentOfType(psiElement, PsiClass::class.java)
            if (parent != null) return parent
        }

        val psiFile: PsiFile? = e.getData(CommonDataKeys.PSI_FILE)
        if (psiFile != null) {
            return PsiTreeUtil.findChildOfType(psiFile, PsiClass::class.java)
        }

        return null
    }

    private fun activateToolWindow(project: Project): ExplanationPanel? {
        val toolWindow = ToolWindowManager.getInstance(project)
            .getToolWindow("Semantic Bridge") ?: return null
        toolWindow.show()
        val content = toolWindow.contentManager.getContent(0) ?: return null
        return content.component as? ExplanationPanel
    }

    private fun showNotification(project: Project, title: String, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("SemanticBridge.Notifications")
            .createNotification(title, content, type)
            .notify(project)
    }
}
