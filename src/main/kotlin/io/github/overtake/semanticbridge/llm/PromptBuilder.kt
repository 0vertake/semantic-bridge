package io.github.overtake.semanticbridge.llm

import io.github.overtake.semanticbridge.model.ClassContext
import io.github.overtake.semanticbridge.model.FieldInfo
import io.github.overtake.semanticbridge.model.MethodInfo

object PromptBuilder {

    val systemPrompt: String = """
        You are an expert software architect analyzing a Java class based on structured
        semantic context extracted from an IDE's program analysis engine (PSI). This context
        includes resolved type hierarchies, dependency relationships, and cross-references
        that are not visible from source text alone.

        Produce a clear, well-structured architectural explanation covering:
        1. Role and responsibility of this class within the project
        2. Key design patterns visible from the type hierarchy and dependencies
        3. Important dependency relationships and their implications
        4. How callers use this class and what that reveals about its purpose
        5. A brief summary of key takeaways

        Formatting rules:
        - Use markdown with ## headings for each section and bullet points for details.
        - Always refer to classes by their simple name (e.g. AuthController, not
          com.ftn.drumigo.controller.AuthController). The fully qualified name is provided
          in the context for your reference but should not appear in the output.
        - Wrap class names, method names, and field names in backticks (e.g. `AuthController`).
        - Be specific and reference actual names from the provided context.
        - Do not speculate beyond what the provided context supports.
        - Do not repeat the raw context data back; synthesize it into insight.
    """.trimIndent()

    fun buildUserPrompt(context: ClassContext): String = buildString {
        appendLine("# Class: ${context.qualifiedName}")
        appendLine()

        appendLine("## Identity")
        appendLine("- Kind: ${context.kind}")
        appendLine("- Module: ${context.module}")
        appendLine("- Package: ${context.packageName}")
        context.superClass?.let { appendLine("- Superclass: $it") }
        if (context.interfaces.isNotEmpty()) {
            appendLine("- Implements: ${context.interfaces.joinToString(", ")}")
        }
        appendLine()

        appendLine("## Public Methods")
        val publicMethods = context.methods.filter { it.visibility.label == "public" }
        if (publicMethods.isEmpty()) {
            appendLine("(none)")
        } else {
            for (m in publicMethods) {
                appendLine("- ${formatMethod(m)}")
            }
        }
        appendLine()

        appendLine("## All Methods (${context.methods.size} total)")
        if (context.methods.isEmpty()) {
            appendLine("(none)")
        } else {
            for (m in context.methods) {
                appendLine("- [${m.visibility}] ${formatMethod(m)}")
            }
        }
        appendLine()

        appendLine("## Fields")
        if (context.fields.isEmpty()) {
            appendLine("(none)")
        } else {
            for (f in context.fields) {
                appendLine("- ${formatField(f)}")
            }
        }
        appendLine()

        if (context.constructorParams.isNotEmpty()) {
            appendLine("## Constructor Parameters")
            for (p in context.constructorParams) {
                appendLine("- ${p.name}: ${p.type}")
            }
            appendLine()
        }

        appendLine("## Callers (${context.callers.size} classes reference this)")
        if (context.callers.isEmpty()) {
            appendLine("(none found)")
        } else {
            for (c in context.callers) {
                appendLine("- $c")
            }
        }
        appendLine()

        appendLine("## Implementations")
        if (context.implementations.isEmpty()) {
            if (context.kind.label == "interface" || context.kind.label == "class") {
                appendLine("(no known implementations)")
            } else {
                appendLine("(not applicable)")
            }
        } else {
            for (impl in context.implementations) {
                appendLine("- $impl")
            }
        }
        appendLine()

        appendLine("## Associated Test")
        appendLine(context.testClass ?: "(no test class found)")
    }

    private fun formatMethod(m: MethodInfo): String {
        val params = m.parameters.joinToString(", ") { "${it.name}: ${it.type}" }
        return "${m.name}($params) -> ${m.returnType}"
    }

    private fun formatField(f: FieldInfo): String =
        "${f.name}: ${f.type} (${f.visibility})"
}
