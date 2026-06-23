---
alwaysApply: true
---

# Koog for AI Agents on the JVM

## Koog Is the Kotlin-Native Agent Framework

- For building AI agents, agent-driven pipelines, sub-agent decomposition, and multi-agent orchestration on JVM, use **[Koog](https://github.com/JetBrains/koog)** (`ai.koog:koog-agents`, current **1.0.0-preview3** — release candidate ahead of the upcoming 1.0 GA; **0.8.0** is the latest stable if you can't take a pre-release)
- Koog is JetBrains' Kotlin-native AI agent framework — type-safe DSL, MCP integration, multiplatform targets, multi-LLM providers (Anthropic, OpenAI, Google, Ollama)
- Don't reach for Python-only frameworks (Claude Agent SDK, LangChain, AutoGen, CrewAI) if your application is Kotlin/JVM. The Python orchestrator + Kotlin sub-agents split is a smell.

## Versions

- **Koog 1.0.0-preview3** runs on **Kotlin 2.3.x** and requires **JDK 17 minimum** (JDK 21 still recommended). The 1.0 preview line splits modules into stable (`1.0.0-preview*`) and beta (`1.0.0-beta-preview*`) streams so production code can pin to APIs that won't break without a deprecation cycle
- Pin via: `implementation("ai.koog:koog-agents:1.0.0-preview3")`
- Breaking changes from 0.7.x / 0.8.0: HTTP transport decoupled from Ktor (LLM clients take a `KoogHttpClient.Factory`), `AgentMemory` removed in favor of `LongTermMemory`, Java blocking methods renamed to `*Blocking`, planners moved to a separate `agents:agents-planners` module. Full list in the initial [`1.0.0-preview` release notes](https://github.com/JetBrains/koog/releases/tag/1.0.0-preview); `preview2` / `preview3` add follow-up bug fixes only

## Idiomatic Use — 1.0.0-preview*

- LLM executors are built via `PromptExecutor.builder().anthropic(apiKey).build()` (same shape for `.openAI(...)`, `.google(...)`, `.ollama(...)`). The 0.7.x / 0.8.0 `simpleAnthropicExecutor(apiKey)` convenience is **gone** in 1.0 — `PromptExecutor.builder()` is the replacement
- `AIAgent(...)` is a top-level factory function and `strategy` is **required**, not optional. The shape is `AIAgent(promptExecutor = ..., llmModel = ..., strategy = ..., systemPrompt = ...)` — the 0.7.x signature without `strategy` does not compile against 1.0
- The simplest strategy is the top-level `functionalStrategy<I, O> { input -> ... }` factory in `ai.koog.agents.core.agent`
- Strategy trap 1 — the lambda takes **one** parameter; `this` is `AIAgentFunctionalContext`, not a second `context` arg
- Strategy trap 2 — `requestLLM(input)` returns `Message.Assistant` with `parts: List<MessagePart>`, not a `.content` / `.text` string; extract via `parts.filterIsInstance<MessagePart.Text>().joinToString("\n") { it.text }` (explicit separator — the default `", "` will insert commas between parts)
- Strategy trap 3 — the Java `AIAgent.builder().functionalStrategy(BiFunction<...>)` overload exists but is **blocking**; Kotlin code uses the top-level `functionalStrategy` factory so `suspend` calls (like `requestLLM`) work
- Don't try to import `anthropicClient(...)` or `AnthropicClientFactory` from `ai.koog.prompt.executor.clients.anthropic` directly — the bytecode advertises them as a top-level facade but they're not resolvable from Kotlin source against Kotlin 2.3.0. Use `PromptExecutor.builder().anthropic(key)` instead
- Models: `AnthropicModels.Sonnet_4` / `Opus_4_*`, `OpenAIModels.Chat.GPT4o`, the relevant Gemini model for Google, Ollama integration for local
- `agent.run(input)` is `suspend` — call from `runBlocking { }` at the top level

## Idiomatic Use — 0.8.0 Stable Fallback

- If you can't take a pre-release, pin `ai.koog:koog-agents:0.8.0`. The 0.8.0 API matches 0.7.3: `simpleAnthropicExecutor(apiKey)` for the executor, `AIAgent(promptExecutor, llmModel, systemPrompt)` for the agent — no `strategy` required, no `functionalStrategy` needed. This is the explicit "stay on stable" code path; everything else in this rule targets the 1.0 preview line

## Sub-Agents and Structured Output

- Sub-agent pattern: wrap an agent as a tool via `AIAgentService.fromAgent(...).createAgentTool(...)`, then register it in the parent's `ToolRegistry`. Each sub-agent invocation gets a **fresh context** — pass anything it needs explicitly via its system prompt (see `jbaruch/sub-agent-delegation`)
- When sub-agents return decisions for downstream code, prompt them to emit JSON matching a schema, then parse with `kotlinx-serialization-json` — more robust than free-form natural language

## Anti-patterns

- ❌ Spawning a Python subprocess (`anthropic-sdk-python`, `claude-agent-sdk`) from a Kotlin app to orchestrate sub-agents
- ❌ Hand-rolling agent loops on top of raw `HttpClient` calls to Anthropic — you'll reinvent retries, conversation history, tool calling, and break before you ship
- ❌ Using `GlobalScope.launch { agent.run(...) }` — unowned scope, leaks; use structured concurrency
- ❌ Sharing a `Predictor` or LLM client across non-coroutine threads
