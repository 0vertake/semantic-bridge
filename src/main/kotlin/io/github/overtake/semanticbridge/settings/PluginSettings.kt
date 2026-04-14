package io.github.overtake.semanticbridge.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

enum class LlmProvider(val displayName: String, val baseUrl: String) {
    GROQ("Groq", "https://api.groq.com/openai/v1"),
    OPENAI("OpenAI", "https://api.openai.com/v1"),
    OPEN_ROUTER("OpenRouter", "https://openrouter.ai/api/v1"),
}

data class LlmModel(val id: String, val displayName: String, val provider: LlmProvider)

val AVAILABLE_MODELS: List<LlmModel> = listOf(
    LlmModel("llama-3.3-70b-versatile", "Llama 3.3 70B", LlmProvider.GROQ),
    LlmModel("llama-3.1-8b-instant", "Llama 3.1 8B Instant", LlmProvider.GROQ),
    LlmModel("gemma2-9b-it", "Gemma 2 9B", LlmProvider.GROQ),
    LlmModel("gpt-4o", "GPT-4o", LlmProvider.OPENAI),
    LlmModel("gpt-4o-mini", "GPT-4o Mini", LlmProvider.OPENAI),
    LlmModel("google/gemini-2.5-flash", "Gemini 2.5 Flash", LlmProvider.OPEN_ROUTER),
    LlmModel("anthropic/claude-sonnet-4", "Claude Sonnet 4", LlmProvider.OPEN_ROUTER),
    LlmModel("deepseek/deepseek-chat-v3", "DeepSeek Chat V3", LlmProvider.OPEN_ROUTER),
)

val DEFAULT_MODEL: LlmModel = AVAILABLE_MODELS.first()

fun findModelById(id: String): LlmModel? = AVAILABLE_MODELS.find { it.id == id }

@State(
    name = "io.github.overtake.semanticbridge.settings.PluginSettings",
    storages = [Storage("SemanticBridge.xml")]
)
class PluginSettings : PersistentStateComponent<PluginSettings.State> {

    data class State(
        var selectedModelId: String = DEFAULT_MODEL.id,
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    var selectedModel: LlmModel
        get() = findModelById(state.selectedModelId) ?: DEFAULT_MODEL
        set(value) { state.selectedModelId = value.id }

    fun getApiKey(provider: LlmProvider): String {
        val attributes = credentialAttributes(provider)
        return PasswordSafe.instance.getPassword(attributes).orEmpty()
    }

    fun setApiKey(provider: LlmProvider, key: String) {
        val attributes = credentialAttributes(provider)
        PasswordSafe.instance.setPassword(attributes, key.ifBlank { null })
    }

    val activeApiKey: String
        get() = getApiKey(selectedModel.provider)

    private fun credentialAttributes(provider: LlmProvider): CredentialAttributes =
        CredentialAttributes(generateServiceName("SemanticBridge", provider.name))

    companion object {
        fun getInstance(): PluginSettings =
            ApplicationManager.getApplication().getService(PluginSettings::class.java)
    }
}
