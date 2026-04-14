# Semantic Bridge -- IntelliJ Plugin

An IntelliJ plugin that extracts rich semantic context from Java classes via PSI APIs (type hierarchies, callers, implementations, test associations), sends it to an LLM, and displays an architectural explanation in a tool window. Built as a JetBrains internship technical task.

## Scope

Primary: one polished end-to-end flow for **Java classes only**. Package and module collectors are stretch goals. v1 does not support Kotlin/Scala (would need UAST).

## Build & Run

```bash
./gradlew build                  # compile + tests
./gradlew test                   # tests only
./gradlew runIde                 # launch sandbox IDE with plugin loaded
./gradlew verifyPlugin           # compatibility checks
./gradlew buildPlugin            # produce installable zip in build/distributions/
```

Target: IntelliJ IDEA 2025.2.4, JDK 21, Kotlin 2.1.20.

## Package Structure

All source under `src/main/kotlin/io/github/overtake/semanticbridge/`:

```
action/         AnAction entry point (right-click menu trigger)
collector/      ClassContextCollector -- PSI-based context extraction
model/          Data classes for semantic context
llm/            LLM HTTP client and prompt construction
ui/             Tool window, panel, markdown renderer
settings/       Persistent plugin settings (model selection, per-provider API keys)
```

Tests under `src/test/kotlin/io/github/overtake/semanticbridge/`.

## Threading Rules -- NEVER VIOLATE

1. **All PSI / VFS / Project Model reads MUST be inside a ReadAction.** Prefer `ReadAction.nonBlocking { }.inSmartMode(project)` for anything non-trivial.
2. **Never do PSI access, index queries, or VFS traversal on EDT.** These go on BGT wrapped in a read action.
3. **All UI updates MUST happen on EDT.** Use `ApplicationManager.getApplication().invokeLater { }`.
4. **Write actions (file creation) MUST happen on EDT** inside `WriteAction.run { }`.
5. **Always check `psiElement.isValid` after crossing a read action boundary.**
6. **Use `.inSmartMode(project)` for any code that touches indices.** Otherwise `IndexNotReadyException`.

## Coding Constraints

- Language: Kotlin. No Java source files.
- No external HTTP libraries -- use `java.net.http.HttpClient` (JDK 21).
- JSON parsing: `org.json` (added as explicit dependency).
- API keys stored per-provider via `com.intellij.credentialStore.PasswordSafe`, never in plain-text settings.
- Model selection persisted as `selectedModelId` in XML state; base URL derived from provider enum at runtime.
- Keep classes small and focused. One public class per file.
- No comments that narrate what the code does. Only comment non-obvious intent or gotchas.

## UI Rendering Rule

For progressive streaming display, **never call `JEditorPane.setText()` repeatedly** -- it resets scroll and flickers. Append via `HTMLDocument.insertBeforeEnd(bodyElement, chunk)` on an initialized HTML skeleton.

## Error Handling

Every error state must have a user-facing message in the tool window panel:
- Missing API key -> direct to Settings
- HTTP 401 -> "Invalid API key"
- HTTP 429 -> "Rate limit exceeded"
- Network error -> "Could not connect"
- Dumb mode -> "Waiting for indexing..."

Never show raw exceptions to the user. Never silently fail.

## Key Reference

- IntelliJ SDK API patterns: `docs/intellij-sdk-api-research.md`
- Full architecture plan: `PLAN.md`
- Session progress: `PROGRESS.md`
