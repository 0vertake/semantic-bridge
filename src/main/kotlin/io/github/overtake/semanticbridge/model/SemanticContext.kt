package io.github.overtake.semanticbridge.model

sealed interface AnalysisTarget {
    val displayName: String

    data class ClassTarget(val qualifiedName: String) : AnalysisTarget {
        override val displayName: String
            get() = qualifiedName.substringAfterLast('.')
    }
}

data class ClassContext(
    val name: String,
    val qualifiedName: String,
    val kind: ClassKind,
    val module: String,
    val packageName: String,
    val superClass: String?,
    val interfaces: List<String>,
    val methods: List<MethodInfo>,
    val fields: List<FieldInfo>,
    val constructorParams: List<FieldInfo>,
    val callers: List<String>,
    val implementations: List<String>,
    val testClass: String?,
)

enum class ClassKind(val label: String) {
    CLASS("class"),
    INTERFACE("interface"),
    ENUM("enum"),
    RECORD("record"),
    ANNOTATION("annotation");

    override fun toString(): String = label
}

data class MethodInfo(
    val name: String,
    val returnType: String,
    val parameters: List<ParameterInfo>,
    val visibility: Visibility,
)

data class ParameterInfo(
    val name: String,
    val type: String,
)

data class FieldInfo(
    val name: String,
    val type: String,
    val visibility: Visibility,
)

enum class Visibility(val label: String) {
    PUBLIC("public"),
    PROTECTED("protected"),
    PACKAGE_PRIVATE("package-private"),
    PRIVATE("private");

    override fun toString(): String = label
}
