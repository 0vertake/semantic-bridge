package io.github.overtake.semanticbridge.collector

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.psi.search.searches.ClassInheritorsSearch
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import io.github.overtake.semanticbridge.model.*

class ClassContextCollector(private val project: Project) {

    fun collect(psiClass: PsiClass, indicator: ProgressIndicator): ClassContext {
        return ReadAction.nonBlocking<ClassContext> {
            indicator.text = "Resolving class..."

            val qualifiedName = psiClass.qualifiedName ?: psiClass.name ?: "Unknown"
            check(psiClass.isValid) { "PSI class is no longer valid: $qualifiedName" }

            val scope = GlobalSearchScope.projectScope(project)

            indicator.text = "Analyzing type hierarchy..."
            val kind = detectKind(psiClass)
            val superClass = psiClass.superClass
                ?.qualifiedName
                ?.takeIf { it != "java.lang.Object" }
            val interfaces = psiClass.interfaces.mapNotNull { it.qualifiedName }

            indicator.text = "Extracting members..."
            val methods = psiClass.methods
                .filter { !it.isConstructor }
                .map { extractMethod(it) }
            val fields = psiClass.fields.map { extractField(it) }
            val constructorParams = extractConstructorParams(psiClass)

            indicator.text = "Searching for callers..."
            val callers = findCallers(psiClass, scope)

            indicator.text = "Searching for implementations..."
            val implementations = if (psiClass.isInterface || psiClass.hasModifierProperty(PsiModifier.ABSTRACT)) {
                findImplementations(psiClass, scope)
            } else {
                emptyList()
            }

            indicator.text = "Looking for associated test..."
            val testClass = findTestClass(psiClass, scope)

            val module = ProjectFileIndex.getInstance(project)
                .getModuleForFile(psiClass.containingFile.virtualFile)
                ?.name ?: "unknown"

            ClassContext(
                name = psiClass.name ?: qualifiedName.substringAfterLast('.'),
                qualifiedName = psiClass.qualifiedName ?: qualifiedName,
                kind = kind,
                module = module,
                packageName = (psiClass.containingFile as? PsiJavaFile)?.packageName ?: "",
                superClass = superClass,
                interfaces = interfaces,
                methods = methods,
                fields = fields,
                constructorParams = constructorParams,
                callers = callers,
                implementations = implementations,
                testClass = testClass,
            )
        }
            .inSmartMode(project)
            .executeSynchronously()
    }

    private fun detectKind(psiClass: PsiClass): ClassKind = when {
        psiClass.isAnnotationType -> ClassKind.ANNOTATION
        psiClass.isEnum -> ClassKind.ENUM
        psiClass.isRecord -> ClassKind.RECORD
        psiClass.isInterface -> ClassKind.INTERFACE
        else -> ClassKind.CLASS
    }

    private fun extractMethod(method: PsiMethod): MethodInfo {
        val params = method.parameterList.parameters.map { param ->
            ParameterInfo(
                name = param.name,
                type = param.type.presentableText,
            )
        }
        return MethodInfo(
            name = method.name,
            returnType = method.returnType?.presentableText ?: "void",
            parameters = params,
            visibility = extractVisibility(method.modifierList),
        )
    }

    private fun extractField(field: PsiField): FieldInfo = FieldInfo(
        name = field.name,
        type = field.type.presentableText,
        visibility = extractVisibility(field.modifierList),
    )

    private fun extractConstructorParams(psiClass: PsiClass): List<FieldInfo> {
        val constructor = psiClass.constructors.firstOrNull() ?: return emptyList()
        return constructor.parameterList.parameters.map { param ->
            FieldInfo(
                name = param.name,
                type = param.type.presentableText,
                visibility = Visibility.PRIVATE,
            )
        }
    }

    private fun extractVisibility(modifierList: PsiModifierList?): Visibility {
        if (modifierList == null) return Visibility.PACKAGE_PRIVATE
        return when {
            modifierList.hasModifierProperty(PsiModifier.PUBLIC) -> Visibility.PUBLIC
            modifierList.hasModifierProperty(PsiModifier.PROTECTED) -> Visibility.PROTECTED
            modifierList.hasModifierProperty(PsiModifier.PRIVATE) -> Visibility.PRIVATE
            else -> Visibility.PACKAGE_PRIVATE
        }
    }

    private fun findCallers(psiClass: PsiClass, scope: GlobalSearchScope): List<String> {
        return ReferencesSearch.search(psiClass, scope)
            .findAll()
            .mapNotNull { ref ->
                val containingClass = PsiTreeUtil.getParentOfType(ref.element, PsiClass::class.java)
                containingClass?.qualifiedName
            }
            .distinct()
            .filter { it != psiClass.qualifiedName }
            .take(MAX_CALLERS)
    }

    private fun findImplementations(psiClass: PsiClass, scope: GlobalSearchScope): List<String> {
        return ClassInheritorsSearch.search(psiClass, scope, true)
            .findAll()
            .mapNotNull { it.qualifiedName }
            .take(MAX_IMPLEMENTATIONS)
    }

    private fun findTestClass(psiClass: PsiClass, scope: GlobalSearchScope): String? {
        val className = psiClass.name ?: return null
        val fileIndex = ProjectFileIndex.getInstance(project)

        val testCandidates = PsiShortNamesCache.getInstance(project)
            .getClassesByName("${className}Test", scope)
        val testClass = testCandidates.firstOrNull { candidate ->
            val vf = candidate.containingFile?.virtualFile ?: return@firstOrNull false
            fileIndex.isInTestSourceContent(vf)
        }
        return testClass?.qualifiedName
    }

    companion object {
        private const val MAX_CALLERS = 20
        private const val MAX_IMPLEMENTATIONS = 20
    }
}
