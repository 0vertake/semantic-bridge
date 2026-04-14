# Progress Tracker

Update this file at the end of each development session.

## Completed

1. build.gradle.kts + plugin.xml -- added `com.intellij.java` dep, `org.json`, JUnit 4, all registrations
2. Data model (SemanticContext.kt) + SemanticContextTest -- 5 tests
3. Settings layer (PluginSettings with PasswordSafe + PluginSettingsConfigurable)
4. MarkdownRenderer + MarkdownRendererTest -- 14 tests
5. LLM client (LlmClient with SSE streaming, typed exceptions) + PromptBuilder + PromptBuilderTest -- 16 tests
6. UI (ExplanationToolWindowFactory + ExplanationPanel) -- 4 states, progressive raw text streaming, final markdown render, toolbar with stop/copy/re-analyze/history
7. ClassContextCollector -- PSI: hierarchy, methods, fields, callers, implementations, test association
8. ExplainArchitectureAction -- wires collector -> prompt -> LLM -> panel, with cancellation and error handling
9. Polish pass: fixed streaming to use raw text during stream + formatted HTML at end, wired re-analyze callback, notification group for missing API key
10. README replaced with project-specific documentation

## In Progress

_Nothing._

## Up Next

- Manual end-to-end test via `runIde`
- Rename repository from `semantic-bridge-mcp` to `semantic-bridge`
- Screenshot/gif for README
- Pre-submission checklist items

## Decisions

- Scope narrowed to class-level analysis only. Package and module collectors are stretch goals.
- Java PSI only for v1. Kotlin/Scala via UAST is a documented planned extension, not an oversight.
- Using `java.net.http.HttpClient` for LLM calls (no external HTTP deps)
- API key stored in PasswordSafe, not plain-text config
- OpenAI-compatible API (configurable base URL for other providers)
- SSE streaming for progressive response rendering
- `org.json:json:20240303` added as explicit dependency (not on IntelliJ classpath by default)
- During streaming: raw escaped text display. On finish: full markdown-to-HTML render. This avoids partial-markdown rendering artifacts from per-token chunks.
- Tool window anchored to the right panel
- History capped at 10 entries, stored in memory only
- JUnit 4 for tests (matches IntelliJ Platform test framework)

## Open Questions

_None remaining for v1 scope._

## Session Log

### Session 1 -- 2026-04-11

**Goal:** Complete full implementation from plan
**Done:** All 10 implementation steps. 36 tests passing. Full build green.
**Blockers:** None
**Notes:** org.json was not on IntelliJ classpath as assumed in original plan -- added as explicit dependency. AllIcons import was `com.intellij.icons.AllIcons`, not `icons.AllIcons`. Streaming markdown rendering per-chunk was broken (partial tokens) -- fixed to use raw text during streaming with formatted render at end.
