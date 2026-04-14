package io.github.overtake.semanticbridge.llm

import io.github.overtake.semanticbridge.model.*
import org.junit.Assert.*
import org.junit.Test

class PromptBuilderTest {

    private fun fullContext() = ClassContext(
        name = "UserService",
        qualifiedName = "com.example.service.UserService",
        kind = ClassKind.CLASS,
        module = "app",
        packageName = "com.example.service",
        superClass = "com.example.service.BaseService",
        interfaces = listOf("com.example.api.UserOperations"),
        methods = listOf(
            MethodInfo("createUser", "User", listOf(ParameterInfo("name", "String")), Visibility.PUBLIC),
            MethodInfo("validate", "boolean", listOf(ParameterInfo("user", "User")), Visibility.PRIVATE),
        ),
        fields = listOf(
            FieldInfo("repository", "UserRepository", Visibility.PRIVATE),
            FieldInfo("eventBus", "EventBus", Visibility.PRIVATE),
        ),
        constructorParams = listOf(
            FieldInfo("repository", "UserRepository", Visibility.PRIVATE),
            FieldInfo("eventBus", "EventBus", Visibility.PRIVATE),
        ),
        callers = listOf("com.example.controller.UserController", "com.example.job.SyncJob"),
        implementations = emptyList(),
        testClass = "com.example.service.UserServiceTest",
    )

    private fun emptyContext() = ClassContext(
        name = "Empty",
        qualifiedName = "com.example.Empty",
        kind = ClassKind.CLASS,
        module = "main",
        packageName = "com.example",
        superClass = null,
        interfaces = emptyList(),
        methods = emptyList(),
        fields = emptyList(),
        constructorParams = emptyList(),
        callers = emptyList(),
        implementations = emptyList(),
        testClass = null,
    )

    @Test
    fun `system prompt is non-empty and contains key instructions`() {
        val prompt = PromptBuilder.systemPrompt
        assertTrue(prompt.isNotBlank())
        assertTrue(prompt.contains("software architect"))
        assertTrue(prompt.contains("semantic context"))
        assertTrue(prompt.contains("design patterns"))
    }

    @Test
    fun `user prompt contains class identity`() {
        val prompt = PromptBuilder.buildUserPrompt(fullContext())
        assertTrue(prompt.contains("# Class: com.example.service.UserService"))
        assertTrue(prompt.contains("Kind: class"))
        assertTrue(prompt.contains("Module: app"))
        assertTrue(prompt.contains("Package: com.example.service"))
    }

    @Test
    fun `user prompt contains superclass and interfaces`() {
        val prompt = PromptBuilder.buildUserPrompt(fullContext())
        assertTrue(prompt.contains("Superclass: com.example.service.BaseService"))
        assertTrue(prompt.contains("Implements: com.example.api.UserOperations"))
    }

    @Test
    fun `user prompt omits superclass when null`() {
        val prompt = PromptBuilder.buildUserPrompt(emptyContext())
        assertFalse(prompt.contains("Superclass:"))
    }

    @Test
    fun `user prompt omits implements when no interfaces`() {
        val prompt = PromptBuilder.buildUserPrompt(emptyContext())
        assertFalse(prompt.contains("Implements:"))
    }

    @Test
    fun `user prompt shows public methods separately`() {
        val prompt = PromptBuilder.buildUserPrompt(fullContext())
        assertTrue(prompt.contains("## Public Methods"))
        assertTrue(prompt.contains("createUser(name: String) -> User"))
        // Private method should not appear in public section
        val publicSection = prompt.substringAfter("## Public Methods").substringBefore("## All Methods")
        assertFalse(publicSection.contains("validate"))
    }

    @Test
    fun `user prompt shows all methods with visibility`() {
        val prompt = PromptBuilder.buildUserPrompt(fullContext())
        assertTrue(prompt.contains("[public] createUser"))
        assertTrue(prompt.contains("[private] validate"))
    }

    @Test
    fun `user prompt shows fields`() {
        val prompt = PromptBuilder.buildUserPrompt(fullContext())
        assertTrue(prompt.contains("repository: UserRepository (private)"))
        assertTrue(prompt.contains("eventBus: EventBus (private)"))
    }

    @Test
    fun `user prompt shows constructor params`() {
        val prompt = PromptBuilder.buildUserPrompt(fullContext())
        assertTrue(prompt.contains("## Constructor Parameters"))
        assertTrue(prompt.contains("repository: UserRepository"))
    }

    @Test
    fun `user prompt omits constructor section when no params`() {
        val prompt = PromptBuilder.buildUserPrompt(emptyContext())
        assertFalse(prompt.contains("## Constructor Parameters"))
    }

    @Test
    fun `user prompt shows callers with count`() {
        val prompt = PromptBuilder.buildUserPrompt(fullContext())
        assertTrue(prompt.contains("Callers (2 classes reference this)"))
        assertTrue(prompt.contains("com.example.controller.UserController"))
        assertTrue(prompt.contains("com.example.job.SyncJob"))
    }

    @Test
    fun `user prompt shows no callers gracefully`() {
        val prompt = PromptBuilder.buildUserPrompt(emptyContext())
        assertTrue(prompt.contains("Callers (0 classes reference this)"))
        assertTrue(prompt.contains("(none found)"))
    }

    @Test
    fun `user prompt shows test class`() {
        val prompt = PromptBuilder.buildUserPrompt(fullContext())
        assertTrue(prompt.contains("com.example.service.UserServiceTest"))
    }

    @Test
    fun `user prompt shows no test class gracefully`() {
        val prompt = PromptBuilder.buildUserPrompt(emptyContext())
        assertTrue(prompt.contains("(no test class found)"))
    }

    @Test
    fun `user prompt for interface shows implementations section`() {
        val interfaceContext = fullContext().copy(
            kind = ClassKind.INTERFACE,
            implementations = listOf("com.example.service.UserServiceImpl"),
        )
        val prompt = PromptBuilder.buildUserPrompt(interfaceContext)
        assertTrue(prompt.contains("com.example.service.UserServiceImpl"))
    }

    @Test
    fun `user prompt handles empty methods and fields`() {
        val prompt = PromptBuilder.buildUserPrompt(emptyContext())
        assertTrue(prompt.contains("(none)"))
    }
}
