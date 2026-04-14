# Architecture Plan

## Overview

The user right-clicks a Java class in the project tree or editor. The plugin:

1. Extracts semantic context via PSI APIs (on BGT with ReadAction)
2. Sends structured context to an OpenAI-compatible LLM API (on BGT, streamed)
3. Displays the architectural explanation in a tool window (on EDT, progressive rendering)

The differentiator: the IDE knows resolved type hierarchies, caller/callee relationships, interface implementations, and test associations -- things a file-reading agent cannot reconstruct.

### Scope

**Primary scope:** One polished end-to-end flow for Java classes. This single path exercises every IntelliJ SDK skill that matters: PSI hierarchy traversal, reference search, inheritor search, index queries, smart mode handling, proper threading.

**Stretch goals (only if primary scope is polished):**
- PackageContextCollector -- package-level analysis
- ModuleContextCollector -- module-level analysis

**Explicit non-goals for v1:**
- Kotlin/Scala/Groovy support (would require UAST; acknowledged as a planned extension in README)
- Multi-file batch analysis
- Persistent caching of results

## File Structure

All under `src/main/kotlin/io/github/overtake/semanticbridge/`:

```
action/
  ExplainArchitectureAction.kt       -- AnAction, determines target, dispatches

collector/
  ClassContextCollector.kt           -- PSI: hierarchy, methods, fields, callers, tests

model/
  SemanticContext.kt                 -- Data classes: ClassContext, MethodInfo, FieldInfo
                                        and the sealed AnalysisTarget type

llm/
  LlmClient.kt                      -- java.net.http.HttpClient, OpenAI chat completions
  PromptBuilder.kt                   -- Converts ClassContext → system + user prompt

ui/
  ExplanationToolWindowFactory.kt    -- Registers tool window with the platform
  ExplanationPanel.kt                -- Panel with HTML display, loading, errors, history
  MarkdownRenderer.kt               -- Converts markdown subset to HTML

settings/
  PluginSettings.kt                  -- PersistentStateComponent + PasswordSafe for API key
  PluginSettingsConfigurable.kt      -- Settings UI under Tools > Semantic Bridge
```

Test sources under `src/test/kotlin/io/github/overtake/semanticbridge/`:

```
llm/
  PromptBuilderTest.kt              -- Unit tests for prompt construction
model/
  SemanticContextTest.kt            -- Unit tests for data model (formatting, edge cases)
ui/
  MarkdownRendererTest.kt           -- Unit tests for markdown-to-HTML conversion
```

## plugin.xml

```xml
<idea-plugin>
    <id>io.github.overtake.semantic-bridge-plugin</id>
    <name>Semantic Bridge</name>
    <vendor>Milos</vendor>

    <description><![CDATA[
        Explains the architectural role of Java classes using IDE semantic analysis and LLM.
        Right-click any class to see its type hierarchy, callers, implementations, and
        test associations analyzed and explained by an AI.
    ]]></description>

    <depends>com.intellij.modules.platform</depends>
    <depends>com.intellij.java</depends>

    <extensions defaultExtensionNs="com.intellij">
        <toolWindow id="Semantic Bridge"
                    factoryClass="io.github.overtake.semanticbridge.ui.ExplanationToolWindowFactory"
                    anchor="right"
                    secondary="true"
                    icon="AllIcons.Actions.Preview"/>

        <applicationConfigurable
            instance="io.github.overtake.semanticbridge.settings.PluginSettingsConfigurable"
            displayName="Semantic Bridge"
            parentId="tools"/>

        <applicationService
            serviceImplementation="io.github.overtake.semanticbridge.settings.PluginSettings"/>
    </extensions>

    <actions>
        <action id="SemanticBridge.ExplainArchitecture"
                class="io.github.overtake.semanticbridge.action.ExplainArchitectureAction"
                text="Explain Architecture"
                description="Generate an AI-powered architecture explanation using IDE semantic context"
                icon="AllIcons.Actions.Preview">
            <add-to-group group-id="ProjectViewPopupMenu" anchor="last"/>
            <add-to-group group-id="EditorPopupMenu" anchor="last"/>
        </action>
    </actions>
</idea-plugin>
```

## build.gradle.kts Changes

Add to the `intellijPlatform` dependencies block:

```kotlin
bundledPlugin("com.intellij.java")
```

The `org.jetbrains.kotlin` bundled plugin is already declared.

No external dependencies needed -- `java.net.http.HttpClient` is in JDK 21, and `org.json` is on the IntelliJ platform classpath.

## Data Model (model/SemanticContext.kt)

```kotlin
sealed interface AnalysisTarget {
    val displayName: String
    data class ClassTarget(val qualifiedName: String) : AnalysisTarget {
        override val displayName get() = qualifiedName.substringAfterLast('.')
    }
}

data class ClassContext(
    val name: String,
    val qualifiedName: String,
    val kind: String,                        // "class", "interface", "enum", "record", "annotation"
    val module: String,
    val packageName: String,
    val superClass: String?,                 // qualified name
    val interfaces: List<String>,            // qualified names
    val methods: List<MethodInfo>,
    val fields: List<FieldInfo>,
    val constructorParams: List<FieldInfo>,   // for DI detection
    val callers: List<String>,               // qualified names of classes that reference this (max 20)
    val implementations: List<String>,       // if interface/abstract: implementing classes
    val testClass: String?,                  // associated test class if found
)

data class MethodInfo(
    val name: String,
    val returnType: String,
    val parameters: List<Pair<String, String>>,  // name to type
    val visibility: String,
)

data class FieldInfo(
    val name: String,
    val type: String,
    val visibility: String,
)
```

## ClassContextCollector

The core of the plugin. All PSI access is wrapped in a single `ReadAction.nonBlocking { }.inSmartMode(project).executeSynchronously()` call inside `Task.Backgroundable`.

APIs and what they extract:

| What | API |
|------|-----|
| Resolve target class | `JavaPsiFacade.getInstance(project).findClass(fqn, GlobalSearchScope.projectScope(project))` |
| Kind detection | `psiClass.isInterface`, `.isEnum`, `.isRecord`, `.isAnnotationType` |
| Superclass | `psiClass.superClass?.qualifiedName` (skip `java.lang.Object`) |
| Interfaces | `psiClass.interfaces.map { it.qualifiedName }` |
| Methods | `psiClass.methods` -- extract name, return type, params, visibility via `PsiModifierList` |
| Fields | `psiClass.fields` -- extract name, type, visibility |
| Constructor params | `psiClass.constructors.firstOrNull()?.parameterList?.parameters` |
| Callers | `ReferencesSearch.search(psiClass, projectScope).findAll()` -- collect containing classes, deduplicate, limit to 20 |
| Implementations | `ClassInheritorsSearch.search(psiClass, projectScope, true).findAll()` -- only if interface/abstract |
| Test class | `PsiShortNamesCache.getInstance(project).getClassesByName(name + "Test", projectScope)` + verify it's in test source root via `ProjectFileIndex` |
| Module | `ProjectFileIndex.getInstance(project).getModuleForFile(psiClass.containingFile.virtualFile)` |

**Validity check:** Always verify `psiClass.isValid` before accessing properties.

**Progress text:** The `Task.Backgroundable` should update `indicator.text` at each stage: "Resolving class...", "Analyzing type hierarchy...", "Searching for callers...", "Waiting for indexing to complete..." (the last one is important when `inSmartMode` blocks during indexing).

## LLM Integration

### LlmClient

- Uses `java.net.http.HttpClient` with HTTP/1.1
- POST to `{baseUrl}/chat/completions`
- Sends JSON body with model, messages (system + user), temperature 0.3, stream: true
- Parses SSE stream: reads `data: ` lines, extracts `choices[0].delta.content`
- Returns content chunks via a callback `(String) -> Unit` for progressive rendering
- Handles `data: [DONE]` as stream termination
- API key from PluginSettings (via PasswordSafe)
- **Cancellation:** Accepts a `() -> Boolean` isCancelled check. On cancel, close the HTTP response input stream to abort the connection.
- **Error handling:** Throw typed exceptions for: missing API key, HTTP 401 (bad key), HTTP 429 (rate limit), network timeout, non-200 responses. The caller maps these to user-facing error messages.

### PromptBuilder

System prompt (fixed):
```
You are an expert software architect. You will receive structured semantic context
extracted from an IDE's program analysis engine. This context includes resolved type
hierarchies, dependency relationships, and cross-references that are not visible from
source text alone.

Produce a clear, well-structured architectural explanation. Focus on:
- The role and responsibility of this class within the project
- Key design patterns visible from the type hierarchy and dependencies
- Important dependency relationships and their implications
- How callers use this class and what that reveals about its purpose

Use markdown formatting with headings and bullet points.
Be specific and reference actual class/method names from the context.
Do not speculate beyond what the provided context supports.
```

User prompt: serialize `ClassContext` to a readable text format with headers and bullet points (not raw JSON). Example:

```
# Class: com.example.service.UserService

## Identity
- Kind: class
- Module: app
- Package: com.example.service
- Superclass: com.example.service.BaseService
- Implements: com.example.api.UserOperations

## Public Methods
- createUser(name: String, email: String) -> User
- findById(id: Long) -> User?
- deleteUser(id: Long) -> void

## Fields
- userRepository: UserRepository (private)
- eventBus: EventBus (private)

## Constructor Parameters
- userRepository: UserRepository
- eventBus: EventBus

## Callers (12 total, showing top 20)
- com.example.controller.UserController
- com.example.job.UserSyncJob
- ...

## Implementations
(not applicable -- this is a concrete class)

## Associated Test
- com.example.service.UserServiceTest
```

## Action Logic (ExplainArchitectureAction)

In `actionPerformed(e: AnActionEvent)`:

1. Determine what was right-clicked:
   - `e.getData(CommonDataKeys.PSI_ELEMENT)` -- check if it's a `PsiClass`
   - If it's a `PsiFile`, find the top-level class in it
   - If the element is inside a class (e.g., user right-clicked a method), walk up via `PsiTreeUtil.getParentOfType(element, PsiClass::class.java)`
2. **Validate API key** -- if missing, show a notification balloon directing to Settings > Tools > Semantic Bridge, then return
3. Build `AnalysisTarget.ClassTarget` from the resolved `PsiClass`
4. Open/activate the Semantic Bridge tool window
5. Launch `Task.Backgroundable("Analyzing architecture...")` that:
   a. Updates progress: "Resolving class..."
   b. Runs ClassContextCollector (in read action, smart mode, on BGT)
   c. Updates progress: "Generating explanation..."
   d. Calls PromptBuilder to construct the prompt
   e. Calls LlmClient.stream() with a chunk callback that posts each chunk to ExplanationPanel via `invokeLater`
   f. On error: posts the error state to ExplanationPanel via `invokeLater`

In `update(e: AnActionEvent)`:
- Enable only when `e.getData(CommonDataKeys.PSI_ELEMENT)` can resolve to a `PsiClass` (directly or by walking up / checking file)
- Disable during dumb mode via `DumbService.isDumb(project)`

## UI (ExplanationPanel)

### Layout

A `SimpleToolWindowPanel(vertical = true)` with:
- **Toolbar** (top): Stop button, Copy button, Re-analyze button, History dropdown
- **Content area**: `JBScrollPane` containing a `JEditorPane` in HTML mode

### States

The panel has four states:

1. **Empty** -- initial state. Shows a centered message: "Right-click a Java class and select 'Explain Architecture' to get started."
2. **Loading** -- analysis + LLM streaming in progress. Shows a `JBLoadingPanel` overlay during the analysis phase, then switches to progressive HTML rendering once streaming starts.
3. **Content** -- explanation is displayed as rendered HTML.
4. **Error** -- shows an inline error message with an appropriate icon. Specific messages for:
   - Missing API key: "API key not configured. Go to Settings > Tools > Semantic Bridge."
   - HTTP 401: "Invalid API key. Check your key in Settings > Tools > Semantic Bridge."
   - HTTP 429: "Rate limit exceeded. Try again in a moment."
   - Network error: "Could not connect to the LLM API. Check your network and base URL."
   - Dumb mode: "IDE is still indexing. Analysis will start when indexing completes."
   - General failure: "Analysis failed: {message}"

### Progressive Rendering (no-flicker)

Do NOT call `JEditorPane.setText()` repeatedly -- it resets scroll position and flickers.

Instead, append to the underlying `HTMLDocument` directly:

```kotlin
val doc = editorPane.document as HTMLDocument
val body = doc.getElement(doc.defaultRootElement, StyleConstants.NameAttribute, HTML.Tag.BODY)
doc.insertBeforeEnd(body, newHtmlChunk)
```

Initialize the pane once with a base HTML skeleton (`<html><body></body></html>`) and append chunks as they arrive.

### MarkdownRenderer

A small utility (~40-50 lines) that converts a markdown subset to HTML. Handle:
- `# Heading` / `## Heading` / `### Heading` -> `<h1>`, `<h2>`, `<h3>`
- `**bold**` -> `<strong>`
- `` `code` `` -> `<code>`
- `- bullet` -> `<ul><li>`
- Blank lines -> `<p>` breaks
- ``` code blocks ``` -> `<pre><code>`

Does NOT need to be a full markdown parser. Just enough for the LLM's typical output format.

### History

Keep a `LinkedList<HistoryEntry>` (max 10) where:

```kotlin
data class HistoryEntry(val targetName: String, val htmlContent: String, val rawMarkdown: String)
```

Toolbar has a dropdown (`ComboBoxAction` or `JBPopupMenu`) showing recent targets by name. Selecting one restores the HTML content instantly without re-running analysis.

### Toolbar Actions

- **Stop** (`AllIcons.Actions.Suspend`): Visible only during loading state. Cancels the background task and LLM stream. Panel shows whatever content has been received so far.
- **Copy** (`AllIcons.Actions.Copy`): Copies the raw markdown (not HTML) to system clipboard.
- **Re-analyze** (`AllIcons.Actions.Refresh`): Re-runs analysis on the last target.
- **History** (`AllIcons.Actions.Back`): Dropdown of recent results.

## Settings

### PluginSettings (PersistentStateComponent)

Persisted state (XML):
- `apiBaseUrl: String` (default: `https://api.openai.com/v1`)
- `modelName: String` (default: `gpt-4o-mini`)

Not in persisted state (PasswordSafe):
- `apiKey: String` (stored/retrieved via `PasswordSafe.instance.getPassword(CredentialAttributes(...))`)

### PluginSettingsConfigurable

Swing form with:
- API Key: `JBPasswordField` with "Test Connection" button
- Base URL: `JBTextField`
- Model: `JBTextField` (free-form so users can use any model)

Registered under Settings > Tools > Semantic Bridge.

## Testing

### Unit Tests (no platform dependencies)

**PromptBuilderTest:**
- Verify system prompt is non-empty and contains key instructions
- Verify user prompt correctly formats a ClassContext with all fields populated
- Verify user prompt handles edge cases: no superclass, no callers, no test class, empty methods list
- Verify interface vs class vs enum formatting differences

**MarkdownRendererTest:**
- Headings at all three levels
- Bold and inline code
- Bullet lists (including nested)
- Code blocks with language tags
- Mixed content (heading + bullets + code)
- Empty input returns empty/minimal HTML

**SemanticContextTest:**
- Verify AnalysisTarget.displayName extracts short name correctly
- Verify data classes handle null/empty fields gracefully

### Integration Tests (if time permits)

**ClassContextCollectorTest** (extends `BasePlatformTestCase`):
- Create a fixture Java file with a known class structure
- Run the collector
- Assert the returned ClassContext matches expected hierarchy, methods, fields

## Implementation Order

Build sequence. Each step should compile and be verifiable before moving on.

1. **build.gradle.kts + plugin.xml** -- Add `com.intellij.java` dependency. Update plugin.xml with all registrations (action, tool window, settings). Verify: `./gradlew build` compiles.
2. **Data model** -- `SemanticContext.kt`. Pure Kotlin, no platform deps. Verify: write SemanticContextTest, run `./gradlew test`.
3. **Settings** -- `PluginSettings` + `PluginSettingsConfigurable`. Verify: `./gradlew runIde`, check Settings > Tools > Semantic Bridge appears and persists values.
4. **MarkdownRenderer** -- `MarkdownRenderer.kt`. Pure Kotlin. Verify: write MarkdownRendererTest, run tests.
5. **LLM client** -- `LlmClient` + `PromptBuilder`. Verify: write PromptBuilderTest. Manual test with a hardcoded ClassContext and real API key via `runIde`.
6. **UI** -- `ExplanationToolWindowFactory` + `ExplanationPanel`. Wire to LlmClient with a dummy ClassContext. Verify: `runIde`, trigger manually, see streaming render in the tool window. Test all four states (empty, loading, content, error).
7. **Class collector** -- `ClassContextCollector`. The core PSI work. Verify: `runIde`, analyze a real class, check the generated ClassContext is correct by inspecting the prompt sent to the LLM.
8. **Action** -- `ExplainArchitectureAction`. Wires everything together. Verify: right-click a class in project tree, see full end-to-end flow.
9. **Polish** -- Error handling edge cases, cancel button behavior, history dropdown, progress text. README replacement.

## Pre-Submission Checklist

- [ ] Replace template README with project-specific one (what it does, screenshot/gif, how to build, architecture rationale)
- [ ] Rename repository from `semantic-bridge-mcp` to `semantic-bridge` (or explain the name in README)
- [ ] Verify `./gradlew build` passes cleanly
- [ ] Verify `./gradlew verifyPlugin` passes
- [ ] All tests pass
- [ ] Settings page works end-to-end
- [ ] At least one successful demo of the full flow (right-click class -> explanation appears)
- [ ] Error states tested: missing API key, bad API key, network failure
- [ ] README explicitly notes: "v1 supports Java source analysis. Kotlin/Scala support via UAST is a planned extension."
