package io.github.overtake.semanticbridge.model

import org.junit.Assert.*
import org.junit.Test

class SemanticContextTest {

    @Test
    fun `ClassTarget displayName extracts short name from qualified name`() {
        val target = AnalysisTarget.ClassTarget("com.example.service.UserService")
        assertEquals("UserService", target.displayName)
    }

    @Test
    fun `ClassTarget displayName handles no package`() {
        val target = AnalysisTarget.ClassTarget("UserService")
        assertEquals("UserService", target.displayName)
    }

    @Test
    fun `ClassKind labels are lowercase`() {
        assertEquals("class", ClassKind.CLASS.label)
        assertEquals("interface", ClassKind.INTERFACE.label)
        assertEquals("enum", ClassKind.ENUM.label)
        assertEquals("record", ClassKind.RECORD.label)
        assertEquals("annotation", ClassKind.ANNOTATION.label)
    }

    @Test
    fun `Visibility labels match Java conventions`() {
        assertEquals("public", Visibility.PUBLIC.label)
        assertEquals("protected", Visibility.PROTECTED.label)
        assertEquals("package-private", Visibility.PACKAGE_PRIVATE.label)
        assertEquals("private", Visibility.PRIVATE.label)
    }

    @Test
    fun `ClassContext handles empty collections`() {
        val context = ClassContext(
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

        assertNull(context.superClass)
        assertNull(context.testClass)
        assertTrue(context.methods.isEmpty())
        assertTrue(context.fields.isEmpty())
        assertTrue(context.callers.isEmpty())
        assertTrue(context.implementations.isEmpty())
    }

    @Test
    fun `ClassContext preserves all populated fields`() {
        val context = ClassContext(
            name = "UserService",
            qualifiedName = "com.example.service.UserService",
            kind = ClassKind.CLASS,
            module = "app",
            packageName = "com.example.service",
            superClass = "com.example.service.BaseService",
            interfaces = listOf("com.example.api.UserOperations"),
            methods = listOf(
                MethodInfo("createUser", "User", listOf(ParameterInfo("name", "String")), Visibility.PUBLIC)
            ),
            fields = listOf(
                FieldInfo("repository", "UserRepository", Visibility.PRIVATE)
            ),
            constructorParams = listOf(
                FieldInfo("repository", "UserRepository", Visibility.PRIVATE)
            ),
            callers = listOf("com.example.controller.UserController"),
            implementations = emptyList(),
            testClass = "com.example.service.UserServiceTest",
        )

        assertEquals("UserService", context.name)
        assertEquals("com.example.service.BaseService", context.superClass)
        assertEquals(1, context.interfaces.size)
        assertEquals(1, context.methods.size)
        assertEquals("createUser", context.methods[0].name)
        assertEquals(1, context.callers.size)
        assertEquals("com.example.service.UserServiceTest", context.testClass)
    }
}
