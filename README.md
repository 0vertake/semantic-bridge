# Semantic Bridge

An IntelliJ IDEA plugin that explains the architectural role of Java classes by combining the IDE's deep semantic analysis with LLM-powered natural language generation.

## What it does

Right-click any Java class in the project tree or editor and select **Explain Architecture**. The plugin:

1. **Extracts semantic context via PSI** -- resolved type hierarchies, caller/callee relationships, interface implementations, constructor dependencies, and test associations. This is structural knowledge that only the IDE's program analysis engine can provide; a file-reading agent cannot reconstruct it.
2. **Sends the structured context to an LLM** (any OpenAI-compatible API) for architectural explanation.
3. **Displays the result** in a tool window with streaming output, history, and copy-to-clipboard.

## Why PSI matters

A text-based agent reading source files can see that `UserService extends BaseService`. The IDE *knows* that `BaseService` implements `Cacheable`, that `UserService` is referenced by 12 other classes across 3 modules, that its constructor takes a `UserRepository` (dependency injection), and that `UserServiceTest` exists in the test source root. This plugin turns that structured knowledge into context an LLM can reason about.

## Quick Start (5 minutes)

The fastest way to try the plugin is with **Groq**, which offers a free API with no credit card required.

1. **Get a Groq API key** -- go to [console.groq.com](https://console.groq.com/), sign in with Google/GitHub, and create an API key from the dashboard. It's instant and free.

2. **Launch the sandbox IDE:**
   ```bash
   ./gradlew runIde
   ```

3. **Configure the plugin** -- in the sandbox IDE, go to **Settings > Tools > Semantic Bridge**. The model defaults to Llama 3.3 70B on Groq. Paste your Groq API key into the Groq field and click OK.

4. **Open any Java project** in the sandbox IDE (or use the IDE's sample projects).

5. **Right-click a Java class** in the project tree or editor and select **Explain Architecture**. The Semantic Bridge tool window will open on the right and stream the LLM's architectural explanation.

> Other supported providers: **OpenAI** ([platform.openai.com/api-keys](https://platform.openai.com/api-keys)) and **OpenRouter** ([openrouter.ai/keys](https://openrouter.ai/keys)). Select a different model from the dropdown and enter the corresponding provider's API key.

## Build & Run

```bash
./gradlew build          # compile + tests
./gradlew test           # tests only
./gradlew runIde         # launch sandbox IDE with plugin loaded
./gradlew buildPlugin    # produce installable zip in build/distributions/
```

**Requirements:** JDK 21, IntelliJ IDEA 2025.2+.

## Architecture

```
action/         Entry point -- AnAction registered in context menus
collector/      PSI-based context extraction (ClassContextCollector)
model/          Data classes for semantic context (ClassContext, MethodInfo, etc.)
llm/            LLM HTTP client (SSE streaming) and prompt construction
ui/             Tool window, JCEF browser panel with marked.js rendering
settings/       Persistent settings with secure API key storage (PasswordSafe)
```

The plugin follows the IntelliJ platform threading model:
- PSI access runs on a background thread inside `ReadAction.nonBlocking { }.inSmartMode(project)`
- LLM streaming runs on a background thread with cancellation support
- All UI updates are posted to EDT via `invokeLater`
- Rendering uses JCEF (embedded Chromium) with JS-side `requestAnimationFrame` pacing

## Planned features

Planned features that extend the plugin's PSI-powered analysis, roughly ordered by impact:

**Package Explainer** -- Right-click a package to get an architectural overview: all classes and their roles, inter-class dependencies, public API surface vs internal classes, and which other packages depend on it. The LLM synthesizes this into a narrative like "this is your service layer; it depends on the repository layer and is consumed by the controller layer."

**Change Impact Analyzer** -- Select a class and see its blast radius: "if you modify this class, these 14 classes are affected, across 3 modules." Uses `ReferencesSearch` to walk the call graph outward. This is something a text-based agent fundamentally cannot do -- it requires resolved cross-references, not string matching.

**Endpoint Tracer** -- Right-click a Spring `@RestController` method and trace the full request path: controller -> service -> repository -> entity. PSI resolves the entire call chain; the LLM explains the business flow.

**Module Explainer** -- Like package explainer but at the Gradle/Maven module level. Uses `ModuleManager` to surface module dependencies, source roots, and API vs implementation boundaries.

**Kotlin/UAST Support** -- Replace Java-specific PSI calls with UAST (Unified Abstract Syntax Tree) to support Kotlin, Scala, and Groovy analysis with the same codebase.

**Comparative Explainer** -- Select two classes and get a side-by-side comparison: how they differ, when to use which, and whether they should be refactored into a shared abstraction.

## Scope & Limitations

- **v1 supports Java source analysis only.** Kotlin, Scala, and Groovy support via UAST is a planned extension.
- Package-level and module-level analysis are planned stretch goals.
- Results are not cached between sessions.

## Tech Stack

- Kotlin 2.1.20
- IntelliJ Platform SDK (2025.2)
- JCEF (embedded Chromium) with marked.js for markdown rendering
- `java.net.http.HttpClient` for LLM API calls (no external HTTP libraries)
- `org.json` for JSON serialization
- JUnit 4 for testing
