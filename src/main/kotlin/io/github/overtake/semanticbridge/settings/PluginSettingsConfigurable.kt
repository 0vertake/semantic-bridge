package io.github.overtake.semanticbridge.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.util.ui.FormBuilder
import java.awt.Component
import javax.swing.*

class PluginSettingsConfigurable : Configurable {

    private var panel: JPanel? = null
    private var modelComboBox: JComboBox<LlmModel>? = null
    private val apiKeyFields = mutableMapOf<LlmProvider, JBPasswordField>()

    override fun getDisplayName(): String = "Semantic Bridge"

    override fun createComponent(): JComponent {
        val settings = PluginSettings.getInstance()

        modelComboBox = JComboBox(AVAILABLE_MODELS.toTypedArray()).apply {
            renderer = ModelListCellRenderer()
            selectedItem = settings.selectedModel
        }

        val builder = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("Model:"), modelComboBox!!, 1, false)
            .addSeparator()

        for (provider in LlmProvider.entries) {
            val field = JBPasswordField().apply { columns = 40 }
            apiKeyFields[provider] = field
            builder.addLabeledComponent(JBLabel("${provider.displayName} API key:"), field, 1, false)
        }

        panel = builder
            .addComponentFillVertically(JPanel(), 0)
            .panel

        return panel!!
    }

    override fun isModified(): Boolean {
        val settings = PluginSettings.getInstance()
        if (modelComboBox?.selectedItem != settings.selectedModel) return true
        for ((provider, field) in apiKeyFields) {
            if (String(field.password) != settings.getApiKey(provider)) return true
        }
        return false
    }

    override fun apply() {
        val settings = PluginSettings.getInstance()
        (modelComboBox?.selectedItem as? LlmModel)?.let { settings.selectedModel = it }
        for ((provider, field) in apiKeyFields) {
            settings.setApiKey(provider, String(field.password))
        }
    }

    override fun reset() {
        val settings = PluginSettings.getInstance()
        modelComboBox?.selectedItem = settings.selectedModel
        for ((provider, field) in apiKeyFields) {
            field.text = settings.getApiKey(provider)
        }
    }

    override fun disposeUIResources() {
        panel = null
        modelComboBox = null
        apiKeyFields.clear()
    }

    private class ModelListCellRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            if (value is LlmModel) {
                text = "${value.displayName}  (${value.provider.displayName})"
            }
            return this
        }
    }
}
