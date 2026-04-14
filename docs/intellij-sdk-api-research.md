# IntelliJ Platform SDK API Research

> Research compiled for building a plugin that exposes IDE semantic context via MCP.
> Sources: JetBrains IntelliJ Platform SDK docs, intellij-community GitHub, JetBrains blog.

---

## 1. PSI (Program Structure Interface)

### Core Classes

| Class | Package | Role |
|-------|---------|------|
| `PsiElement` | `com.intellij.psi` | Base interface for all PSI nodes |
| `PsiFile` | `com.intellij.psi` | Root of the PSI tree for a file |
| `PsiJavaFile` | `com.intellij.psi` | Java-specific file root (extends `PsiFile`) |
| `PsiClass` | `com.intellij.psi` | Represents a Java class/interface/enum/record |
| `PsiMethod` | `com.intellij.psi` | Represents a Java method |
| `PsiField` | `com.intellij.psi` | Represents a Java field |
| `PsiReference` | `com.intellij.psi` | A reference from one element to another |
| `PsiNamedElement` | `com.intellij.psi` | Any element that has a name (target of references) |

### Getting the PSI Tree for a File

```kotlin
// From a VirtualFile
val psiFile: PsiFile? = PsiManager.getInstance(project).findFile(virtualFile)

// From an Editor/Document
val psiFile: PsiFile? = PsiDocumentManager.getInstance(project).getPsiFile(document)

// From an AnAction
val psiFile: PsiFile? = e.getData(CommonDataKeys.PSI_FILE)
val psiElement: PsiElement? = e.getData(CommonDataKeys.PSI_ELEMENT)

// Find element at a specific offset
val element: PsiElement? = psiFile.findElementAt(offset)
```

### Navigating the PSI Tree

**Top-down (parent → children):** Use visitors.

```kotlin
// Java-specific recursive visitor
psiFile.accept(object : JavaRecursiveElementVisitor() {
    override fun visitMethod(method: PsiMethod) {
        super.visitMethod(method)
        // process method
    }
    override fun visitClass(aClass: PsiClass) {
        super.visitClass(aClass)
        // process class
    }
})
```

**Bottom-up (child → parent):**

```kotlin
// Walk up to find the containing method
val method = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)

// Or use specific APIs
val containingClass = method.containingClass
val containingFile = element.containingFile
```

**Siblings and children:**

```kotlin
val children: Array<PsiElement> = element.children
val firstChild: PsiElement? = element.firstChild
val nextSibling: PsiElement? = element.nextSibling
// Utility: find specific child types
val methods: Array<PsiMethod> = psiClass.methods
val fields: Array<PsiField> = psiClass.fields
val innerClasses: Array<PsiClass> = psiClass.innerClasses
```

### Finding Classes and Methods

```kotlin
// Find class by fully qualified name
val psiClass: PsiClass? = JavaPsiFacade.getInstance(project)
    .findClass("com.example.MyClass", GlobalSearchScope.projectScope(project))

// Find class by short name
val classes: Array<PsiClass> = PsiShortNamesCache.getInstance(project)
    .getClassesByName("MyClass", GlobalSearchScope.projectScope(project))

// Get superclass
val superClass: PsiClass? = psiClass.superClass

// Find all inheritors
val inheritors: Query<PsiClass> = ClassInheritorsSearch.search(psiClass)
inheritors.forEach { inheritor -> /* process */ }

// Find overriding methods
val overriders: Query<PsiMethod> = OverridingMethodsSearch.search(psiMethod)
```

### Resolving References

```kotlin
// Resolve a reference to its declaration
val reference: PsiReference? = element.reference
val resolved: PsiElement? = reference?.resolve()

// Find all references to an element
val refs: Collection<PsiReference> = ReferencesSearch.search(psiElement).findAll()

// Multi-resolve (for overloaded/ambiguous references)
if (reference is PsiPolyVariantReference) {
    val results: Array<ResolveResult> = reference.multiResolve(false)
}
```

### UAST (Unified AST)

For cross-language support (Java, Kotlin, Groovy, Scala), use UAST:

```kotlin
// Convert PSI to UAST
val uFile: UFile? = psiFile.toUElement() as? UFile
val uMethod: UMethod? = psiMethod.toUElement() as? UMethod

// UAST classes: UFile, UClass, UMethod, UField, UExpression, etc.
// Package: org.jetbrains.uast
```

> **Key insight:** UAST is recommended if the plugin must work across JVM languages.
> Add `<depends>com.intellij.java</depends>` to `plugin.xml` for Java PSI.

---

## 2. Compiler/Analysis: Errors and Warnings

### DaemonCodeAnalyzer — Background Highlighting

The primary way the IDE surfaces errors/warnings is through the daemon code analyzer,
which runs highlighting passes in the background.

```kotlin
// Get the analyzer instance
val analyzer = DaemonCodeAnalyzer.getInstance(project)

// Force rehighlight of a file
analyzer.restart(psiFile)
```

### Reading HighlightInfo (Errors/Warnings) from a File

```kotlin
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
import com.intellij.codeInsight.daemon.impl.HighlightInfo

// Get all highlights for a document (must be called in a read action)
val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)!!
val highlights: List<HighlightInfo> = DaemonCodeAnalyzerImpl
    .getHighlights(document, HighlightSeverity.WARNING, project)
// Filter by severity:
//   HighlightSeverity.ERROR, WARNING, INFO, WEAK_WARNING, etc.
```

> **Package:** `com.intellij.codeInsight.daemon.impl`
>
> **Gotcha:** Highlights are available only after the daemon has run on the file.
> If the file hasn't been analyzed yet, the list will be empty.
> Use `DaemonCodeAnalyzerImpl.getFileStatusMap()` to check analysis state.

### Annotator API (for custom analysis)

```kotlin
// Implement com.intellij.lang.annotation.Annotator
class MyAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (/* condition */) {
            holder.newAnnotation(HighlightSeverity.ERROR, "Error message")
                .range(element.textRange)
                .create()
        }
    }
}
// Register in plugin.xml:
// <annotator language="JAVA" implementationClass="com.example.MyAnnotator"/>
```

### ExternalAnnotator (for slow external tools)

```kotlin
// For linters, compilers, etc. that are too slow for the main highlighting pass.
// Extends ExternalAnnotator<InitialInfoType, AnnotationResultType>
// Three phases: collectInformation() → doAnnotate() → apply()
// Register: <externalAnnotator language="JAVA" implementationClass="..."/>
```

### Inspections API

```kotlin
// Run inspections programmatically
val profile = InspectionProjectProfileManager.getInstance(project).currentProfile
val tools: List<Tools> = profile.getAllEnabledInspectionTools(project)

// Get inspection results for a file via InspectionEngine
// Package: com.intellij.codeInspection
val wrapper: InspectionToolWrapper<*, *> = /* get from profile */
val problems: List<ProblemDescriptor> = InspectionEngine
    .runInspectionOnFile(psiFile, wrapper, GlobalInspectionContext)
```

### CompilerManager

```kotlin
// Trigger compilation
val compilerManager = CompilerManager.getInstance(project)
compilerManager.make(CompileStatusNotification { aborted, errors, warnings, context ->
    // Handle compilation result
})

// Listen to compilation events
compilerManager.addCompilationStatusListener(object : CompilationStatusListener {
    override fun compilationFinished(aborted: Boolean, errors: Int, warnings: Int, compileContext: CompileContext) {
        // React to compilation completion
    }
})
```

> **Package:** `com.intellij.openapi.compiler`

---

## 3. Project Model

### Project Hierarchy

```
Project
  └── Module (1..N)
       ├── Content Root (1..N)  — directories on disk
       │    ├── Source Root (src/main/java, src/main/kotlin)
       │    ├── Test Source Root (src/test/java)
       │    └── Excluded Root (build/, .gradle/)
       └── Order Entries (dependencies)
            ├── Module SDK
            ├── Library dependencies
            └── Module dependencies
```

### Key APIs

```kotlin
// Get all modules
val modules: Array<Module> = ModuleManager.getInstance(project).modules

// Find module by name
val module: Module? = ModuleManager.getInstance(project).findModuleByName("app")

// Get source roots for a module
val sourceRoots: Array<VirtualFile> = ModuleRootManager.getInstance(module).sourceRoots
val contentRoots: Array<VirtualFile> = ModuleRootManager.getInstance(module).contentRoots

// Project-wide source roots
val allSourceRoots: Array<VirtualFile> = ProjectRootManager.getInstance(project).contentSourceRoots

// Project SDK
val sdk: Sdk? = ProjectRootManager.getInstance(project).projectSdk
```

### ProjectFileIndex — Fast Queries

```kotlin
val fileIndex = ProjectFileIndex.getInstance(project)

// Which module owns this file?
val module: Module? = fileIndex.getModuleForFile(virtualFile)

// Is this file in source/test/library?
val isSource: Boolean = fileIndex.isInSourceContent(virtualFile)
val isTest: Boolean = fileIndex.isInTestSourceContent(virtualFile)
val isLibrary: Boolean = fileIndex.isInLibrarySource(virtualFile)

// Content root for a file
val contentRoot: VirtualFile? = fileIndex.getContentRootForFile(virtualFile)
val sourceRoot: VirtualFile? = fileIndex.getSourceRootForFile(virtualFile)
```

### Dependencies via OrderEnumerator

```kotlin
// All dependency classes for a module
val classRoots: Array<VirtualFile> = OrderEnumerator.orderEntries(module)
    .recursively()
    .classes()
    .roots

// Only production compile dependencies (no test, no SDK)
val libRoots: Array<VirtualFile> = OrderEnumerator.orderEntries(module)
    .withoutSdk()
    .productionOnly()
    .compileOnly()
    .classes()
    .roots

// All project dependencies
val projectClasspath: Array<VirtualFile> = OrderEnumerator.orderEntries(project)
    .classes()
    .roots
```

> **Packages:**
> - `com.intellij.openapi.module.ModuleManager`
> - `com.intellij.openapi.roots.ProjectRootManager`
> - `com.intellij.openapi.roots.ModuleRootManager`
> - `com.intellij.openapi.roots.ProjectFileIndex`
> - `com.intellij.openapi.roots.OrderEnumerator`

---

## 4. Indexing: StubIndex and FileBasedIndex

### FileBasedIndex

File-based indices map file contents to key-value pairs. Used for fast lookups without parsing full PSI.

```kotlin
// Query an existing index
val fileIndex = FileBasedIndex.getInstance()

// Example: find files containing a word
val files: Collection<VirtualFile> = fileIndex.getContainingFiles(
    IdIndex.NAME,      // index ID
    IdIndexEntry("myIdentifier", true),
    GlobalSearchScope.projectScope(project)
)

// Iterate over values
fileIndex.processValues(
    indexId,
    key,
    null,  // restrict to specific file, or null for all
    { file, value -> /* process */ true },
    GlobalSearchScope.projectScope(project)
)
```

> **Package:** `com.intellij.util.indexing.FileBasedIndex`
> **Extension point:** `com.intellij.fileBasedIndex`

### StubIndex

Stub indices are built on top of serialized PSI stubs — lightweight representations of
externally visible declarations (classes, methods, fields).

```kotlin
// Find elements by stub index key
val classes: Collection<PsiClass> = StubIndex.getElements(
    JavaStubIndexKeys.CLASS_SHORT_NAMES,
    "MyClass",
    project,
    GlobalSearchScope.projectScope(project),
    PsiClass::class.java
)

// Check if a key exists
val hasKey: Boolean = StubIndex.getInstance().processElements(
    indexKey, "name", project, scope, PsiClass::class.java
) { /* processor */ true }
```

> **Package:** `com.intellij.psi.stubs.StubIndex`
> **Extension point:** `com.intellij.stubIndex`

### Find Usages (Programmatic)

```kotlin
// Find all references to a PsiElement
val usages: Collection<PsiReference> = ReferencesSearch.search(
    psiElement,
    GlobalSearchScope.projectScope(project)
).findAll()

// Find method references specifically
val methodRefs: Collection<PsiReference> = MethodReferencesSearch.search(
    psiMethod,
    GlobalSearchScope.projectScope(project),
    true  // strictSignatureSearch
).findAll()
```

### Find Implementations

```kotlin
// Find all implementations of an interface/abstract class
val implementations: Collection<PsiClass> = ClassInheritorsSearch.search(
    psiClass,
    GlobalSearchScope.projectScope(project),
    true  // deep search
).findAll()

// Find overriding methods
val overrides: Collection<PsiMethod> = OverridingMethodsSearch.search(
    psiMethod
).findAll()

// Find implementations via "Go to Implementation"
val targets: Array<PsiElement> = DefinitionsScopedSearch.search(psiElement).toArray(PsiElement.EMPTY_ARRAY)
```

### Search Scopes

```kotlin
// Commonly used scopes
GlobalSearchScope.projectScope(project)       // all project files
GlobalSearchScope.allScope(project)            // project + libraries
GlobalSearchScope.moduleScope(module)          // single module
GlobalSearchScope.fileScope(psiFile)           // single file
psiElement.useScope                            // smart narrowed scope
```

> **Package for searches:** `com.intellij.psi.search.searches`
> Classes: `ReferencesSearch`, `ClassInheritorsSearch`, `OverridingMethodsSearch`,
> `MethodReferencesSearch`, `DefinitionsScopedSearch`

---

## 5. Refactoring

### Rename

```kotlin
// Programmatic rename via RefactoringFactory
val factory = RefactoringFactory.getInstance(project)
val rename = factory.createRename(psiElement, "newName")
rename.run()  // executes the rename across all references

// Rename with options
val rename = factory.createRename(
    psiElement,
    "newName",
    true,   // searchInComments
    true    // searchInNonJavaFiles
)
rename.run()
```

Under the hood, rename calls:
- `PsiNamedElement.setName("newName")` on the target
- `PsiReference.handleElementRename("newName")` on all references

### Safe Delete

```kotlin
val safeDelete = factory.createSafeDelete(arrayOf(psiElement))
safeDelete.run()
```

### Other Refactorings

For extract method, inline, move, etc., the public API is more limited. Use `RefactoringActionHandlerFactory`:

```kotlin
val handlerFactory = RefactoringActionHandlerFactory.getInstance()

// Extract method (requires editor context)
val extractMethodHandler = handlerFactory.createExtractMethodHandler()
extractMethodHandler.invoke(project, editor, psiFile, dataContext)

// Inline
val inlineHandler = handlerFactory.createInlineHandler()
inlineHandler.invoke(project, editor, psiFile, dataContext)
```

> **Packages:**
> - `com.intellij.refactoring.RefactoringFactory`
> - `com.intellij.refactoring.RefactoringActionHandlerFactory`

### Gotcha

Most refactoring handlers require a write action and must run on EDT. Extract and inline
typically need an active editor selection. For headless/programmatic use, you may need to
directly instantiate processor classes like `ExtractMethodProcessor`.

---

## 6. Run Configurations

### Accessing Existing Configurations

```kotlin
val runManager = RunManager.getInstance(project)

// List all configurations
val allConfigs: List<RunnerAndConfigurationSettings> = runManager.allSettings

// Get selected configuration
val selected: RunnerAndConfigurationSettings? = runManager.selectedConfiguration

// Find by name
val config: RunnerAndConfigurationSettings? = runManager.findConfigurationByName("My App")

// Find by type
val javaConfigs = runManager.getConfigurationSettingsList(
    ConfigurationTypeUtil.findConfigurationType("Application")
)
```

### Executing a Configuration

```kotlin
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.runners.ExecutionEnvironmentBuilder

// Run
val executor = DefaultRunExecutor.getRunExecutorInstance()
val environment = ExecutionEnvironmentBuilder
    .createOrNull(executor, config)
    ?.build()
if (environment != null) {
    ProgramRunnerUtil.executeConfiguration(environment, false, true)
}

// Debug
val debugExecutor = DefaultDebugExecutor.getDebugExecutorInstance()
val debugEnv = ExecutionEnvironmentBuilder
    .createOrNull(debugExecutor, config)
    ?.build()
```

### Creating a Configuration Programmatically

```kotlin
val type = ConfigurationTypeUtil.findConfigurationType("Application")
val factory = type.configurationFactories[0]
val settings = runManager.createConfiguration("My New Config", factory)

// Configure it
val runConfig = settings.configuration as ApplicationConfiguration
runConfig.mainClassName = "com.example.Main"
runConfig.setModule(module)

// Add and select
runManager.addConfiguration(settings)
runManager.selectedConfiguration = settings
```

> **Packages:**
> - `com.intellij.execution.RunManager`
> - `com.intellij.execution.ProgramRunnerUtil`
> - `com.intellij.execution.executors.DefaultRunExecutor`
> - `com.intellij.execution.runners.ExecutionEnvironmentBuilder`
> - `com.intellij.execution.configurations.ConfigurationTypeUtil`

---

## 7. VCS / Git

### General VCS API

```kotlin
// Get VCS manager
val vcsManager = ProjectLevelVcsManager.getInstance(project)
val allVcs: Array<AbstractVcs> = vcsManager.allActiveVcss

// Get changed files via ChangeListManager
val changeListManager = ChangeListManager.getInstance(project)
val changes: Collection<Change> = changeListManager.allChanges
val defaultChangeList: LocalChangeList = changeListManager.defaultChangeList

// Iterate changes
for (change in changes) {
    val type: Change.Type = change.type  // MODIFICATION, NEW, DELETED, MOVED
    val virtualFile: VirtualFile? = change.virtualFile
    val beforeRevision: ContentRevision? = change.beforeRevision
    val afterRevision: ContentRevision? = change.afterRevision
}

// Get modified (dirty) files
val dirtyFiles: Collection<VirtualFile> = changeListManager.modifiedWithoutEditing
```

### Git-Specific API (git4idea)

**Requires** `<depends>Git4Idea</depends>` in `plugin.xml`.

```kotlin
import git4idea.repo.GitRepositoryManager
import git4idea.repo.GitRepository
import git4idea.branch.GitBranchUtil

// Get all git repos in the project
val gitManager = GitRepositoryManager.getInstance(project)
val repos: List<GitRepository> = gitManager.repositories

for (repo in repos) {
    // Current branch
    val branch: GitLocalBranch? = repo.currentBranch
    val branchName: String? = branch?.name

    // Repository state
    val state: GitRepository.State = repo.state  // NORMAL, REBASING, MERGING, DETACHED, GRAFTED

    // All local branches
    val localBranches: Collection<GitLocalBranch> = repo.branches.localBranches

    // All remote branches
    val remoteBranches: Collection<GitRemoteBranch> = repo.branches.remoteBranches

    // Remotes
    val remotes: Collection<GitRemote> = repo.remotes
}
```

> **Packages:**
> - `com.intellij.openapi.vcs.ProjectLevelVcsManager`
> - `com.intellij.openapi.vcs.changes.ChangeListManager`
> - `com.intellij.openapi.vcs.changes.Change`
> - `git4idea.repo.GitRepositoryManager` (requires Git4Idea dependency)
> - `git4idea.repo.GitRepository`

---

## 8. Threading Model

### The Rules

| What | Thread | Lock needed |
|------|--------|-------------|
| Read PSI/VFS/Project model | Any thread | Read lock (explicit or implicit) |
| Write PSI/VFS/Project model | EDT only | Write lock (explicit) |
| UI operations | EDT only | Write intent lock (implicit on EDT) |
| Long computations | BGT only | None (use NBRA for data access) |

### Read Actions

```kotlin
// Simple read action (blocks if write lock is held)
val result = ReadAction.compute<PsiFile, Throwable> {
    PsiManager.getInstance(project).findFile(virtualFile)
}

// Kotlin coroutine style (2024.1+, preferred)
val result = readAction {
    PsiManager.getInstance(project).findFile(virtualFile)
}

// Non-blocking read action (cancels on write, retries automatically)
ReadAction.nonBlocking<PsiFile> {
    PsiManager.getInstance(project).findFile(virtualFile)
}
    .inSmartMode(project)       // wait for indexing
    .expireWith(disposable)     // auto-cancel when disposed
    .finishOnUiThread(ModalityState.defaultModalityState()) { result ->
        // use result on EDT
    }
    .submit(AppExecutorUtil.getAppExecutorService())
```

### Write Actions

```kotlin
// Simple write action (must be on EDT)
WriteAction.run<Throwable> {
    psiFile.delete()
}

// Kotlin coroutine style (2024.1+, preferred)
writeAction {
    psiFile.delete()
}

// Schedule write action on EDT from background
ApplicationManager.getApplication().invokeLater({
    WriteAction.run<Throwable> {
        // modify PSI/VFS
    }
}, ModalityState.defaultModalityState())
```

### Checking Thread Context

```kotlin
ApplicationManager.getApplication().isDispatchThread    // are we on EDT?
ApplicationManager.getApplication().isReadAccessAllowed  // is read lock held?
ApplicationManager.getApplication().isWriteAccessAllowed // is write lock held?
```

### Smart Mode (Indexing Awareness)

```kotlin
// Check if indices are ready
val isDumb: Boolean = DumbService.isDumb(project)

// Run code when smart (indices ready)
DumbService.getInstance(project).runWhenSmart {
    // safe to use indices here
}

// Schedule EDT work after indexing
DumbService.getInstance(project).smartInvokeLater {
    // safe to use indices on EDT
}
```

---

## 9. Key Gotchas and Best Practices

### What Requires Read Lock
- Accessing PSI tree (any `PsiElement` navigation)
- Accessing VFS (`VirtualFile` properties beyond `getPath()`)
- Accessing Project root model (modules, source roots)
- Resolving references
- Querying indices (StubIndex, FileBasedIndex)

### What Requires Write Lock (EDT Only)
- Modifying PSI (adding/deleting/renaming elements)
- Creating/deleting/renaming files in VFS
- Modifying project structure (adding modules, changing dependencies)
- Running refactorings

### What Must Run on EDT
- All UI operations (showing dialogs, updating tool windows)
- Write actions
- `AnAction.actionPerformed()` (by convention)

### Performance Gotchas

1. **`PsiElement.getText()` is expensive** — traverses the entire subtree and concatenates strings. Use `textMatches(String)` for comparisons instead.

2. **`getTextRange()`, `getContainingFile()`, `getProject()`** — traverse up to file root. Cache these when processing many elements.

3. **Don't load many AST trees simultaneously** — use stubs for non-editor files. Only files open in editors should have full ASTs in memory. Use `AstLoadingFilter` to detect accidental AST loading.

4. **Non-cancellable read actions in BGT freeze the UI** — a `ReadAction.compute()` on BGT blocks all write actions (and thus EDT). Always prefer `ReadAction.nonBlocking()` or coroutine `readAction` for long operations.

5. **Objects don't survive between read actions** — always re-validate PSI/VFS objects at the start of a new read action:
   ```kotlin
   if (!psiElement.isValid) return  // element may have been deleted
   ```

6. **Event listeners must be lightweight** — don't do heavy work in `PsiTreeChangeListener`, `VirtualFileListener`, etc. Schedule background processing with `MergingUpdateQueue`.

7. **Indices may not be ready (dumb mode)** — during indexing, many PSI operations throw `IndexNotReadyException`. Use `DumbService.runWhenSmart()` or `ReadAction.nonBlocking().inSmartMode()`.

8. **`invokeLater` vs `SwingUtilities.invokeLater`** — always use `Application.invokeLater()` which supports `ModalityState`. Never use `SwingUtilities.invokeLater()` for data model modifications.

9. **Cache expensive computations** — use `CachedValuesManager.getCachedValue()` with appropriate dependency tracking (`PsiModificationTracker`, `ProjectRootManager`).

10. **File-based operations on EDT** — never traverse VFS, parse PSI, resolve references, or query indices on EDT. All of these should be on BGT with proper read actions.

### Recommended Patterns for a Semantic Context Plugin

```kotlin
// Pattern: Safely read PSI from any thread
fun getClassInfo(project: Project, fqn: String): ClassInfo? {
    return ReadAction.nonBlocking<ClassInfo?> {
        val psiClass = JavaPsiFacade.getInstance(project)
            .findClass(fqn, GlobalSearchScope.projectScope(project))
            ?: return@nonBlocking null

        ClassInfo(
            name = psiClass.name ?: "",
            qualifiedName = psiClass.qualifiedName ?: "",
            methods = psiClass.methods.map { it.name },
            fields = psiClass.fields.map { it.name },
            superClass = psiClass.superClass?.qualifiedName
        )
    }
        .inSmartMode(project)
        .executeSynchronously()  // blocks current thread until result
}

// Pattern: Collect diagnostics
fun getDiagnostics(project: Project, psiFile: PsiFile): List<Diagnostic> {
    return ReadAction.compute<List<Diagnostic>, Throwable> {
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
            ?: return@compute emptyList()
        DaemonCodeAnalyzerImpl.getHighlights(document, null, project)
            .map { Diagnostic(it.description, it.severity.name, it.startOffset, it.endOffset) }
    }
}
```

---

## Appendix: Key Extension Points

| Extension Point | Purpose |
|----------------|---------|
| `com.intellij.annotator` | Custom error/warning highlighting |
| `com.intellij.externalAnnotator` | Slow external tool integration |
| `com.intellij.localInspection` | Custom code inspections |
| `com.intellij.fileBasedIndex` | Custom file-based index |
| `com.intellij.stubIndex` | Custom stub index |
| `com.intellij.lang.findUsagesProvider` | Custom find usages for a language |
| `com.intellij.referencesSearch` | Extend reference search |
| `com.intellij.renameInputValidator` | Validate rename input |
| `compiler.task` | Custom compilation task |

## Appendix: plugin.xml Dependencies

```xml
<!-- Java/JVM support (required for PsiClass, PsiMethod, etc.) -->
<depends>com.intellij.java</depends>

<!-- Git support (required for git4idea APIs) -->
<depends>Git4Idea</depends>

<!-- Optional: Kotlin support -->
<depends optional="true" config-file="kotlin-support.xml">org.jetbrains.kotlin</depends>
```

## Appendix: Key Official Documentation Links

- [PSI Overview](https://plugins.jetbrains.com/docs/intellij/psi.html)
- [PSI Cookbook](https://plugins.jetbrains.com/docs/intellij/psi-cookbook.html)
- [PSI Performance](https://plugins.jetbrains.com/docs/intellij/psi-performance.html)
- [Threading Model](https://plugins.jetbrains.com/docs/intellij/threading-model.html)
- [Indexing and PSI Stubs](https://plugins.jetbrains.com/docs/intellij/indexing-and-psi-stubs.html)
- [Project Model](https://plugins.jetbrains.com/docs/intellij/project-model.html)
- [VCS Integration](https://plugins.jetbrains.com/docs/intellij/vcs-integration-for-plugins.html)
- [Execution](https://plugins.jetbrains.com/docs/intellij/execution.html)
- [Find Usages](https://plugins.jetbrains.com/docs/intellij/find-usages.html)
- [Rename Refactoring](https://plugins.jetbrains.com/docs/intellij/rename-refactoring.html)
- [UAST](https://plugins.jetbrains.com/docs/intellij/uast.html)
