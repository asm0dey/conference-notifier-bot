# jbaruch/kotlin-tutor

[![tessl](https://img.shields.io/endpoint?url=https%3A%2F%2Fapi.tessl.io%2Fv1%2Fbadges%2Fjbaruch%2Fkotlin-tutor)](https://tessl.io/registry/jbaruch/kotlin-tutor)

Teaches AI coding agents to write idiomatic Kotlin instead of Java-in-a-`.kt`-file — AND to make the right stack choices on JVM. Thirteen `alwaysApply` rules cover both the highest-leverage Kotlin idioms and the canonical library + tooling defaults (Kotlin 2.3, JDK 21, Gradle Kotlin DSL, Ktor, coroutines, DJL, JavaCV, Koog). Four skills handle the common conversions and an API-design review covering the situational guidance that doesn't earn always-on context cost, plus a CI script that gates the JUnit-to-Kotest migration deterministically.

## Why This Plugin Exists

A coding agent without Kotlin context produces one of two failures:

1. **Not Kotlin at all.** Ask it to "write a program that turns on my smart bulb" and you get Python with `requests`, because that's the median answer in training data — even when you're in a Kotlin Gradle project.
2. **Kotlin that's actually Java.** Regular `class` instead of `data class`, manual `equals` / `hashCode`, `var` properties everywhere, `Optional<String>` for nullables, JUnit assertions in test files. Compiles, runs, but it's Java in a `.kt` file.

One `tessl install` closes both gaps. The agent learns: (a) the JVM has first-class Kotlin libraries for the tasks you actually need (Ktor for HTTP, DJL for ML, JavaCV for vision, Koog for agents) so the answer to "turn on my bulb" is a Kotlin program, not a Python script; and (b) when it writes that Kotlin, it writes idiomatic Kotlin.

## Installation

```
tessl install jbaruch/kotlin-tutor
```

## What's Included

### Rules (13)

#### Idiom rules — write Kotlin, not Java-in-a-`.kt`

| ID | Rule | Summary |
|----|------|---------|
| K-1 | [prefer-val](rules/prefer-val.md) | Properties default to `val`; `var` is opt-in for mutation |
| K-2 | [nullable-question-mark](rules/nullable-question-mark.md) | `String?` not `Optional<String>`; lean on `?.` and `?:` |
| K-3 | [use-data-class](rules/use-data-class.md) | Value types are `data class`; never hand-roll equals/hashCode/toString |
| K-4 | [kotest-over-junit](rules/kotest-over-junit.md) | Tests use Kotest matchers and specs, not `assertEquals` and `@Test` |
| K-5 | [prefer-stdlib-scope](rules/prefer-stdlib-scope.md) | Use `.let` / `.also` / `.apply` over imperative Java patterns |
| K-6 | [extension-over-util](rules/extension-over-util.md) | Extension functions, not `*Utils` classes with static methods |
| K-7 | [stateflow-over-atomic-polling](rules/stateflow-over-atomic-polling.md) | `MutableStateFlow<T?>` (or `MutableStateFlow<T>` with a default) for single-writer / many-reader reactive state — not `AtomicReference<T?>` + a `delay`-poll loop |

The idiom rules govern application and internal code — the per-line idioms worth their always-on context cost. Broader API-design judgement (boolean args, read-only collections, validation, sealed hierarchies, `toString`, backward compatibility, multiplatform, documentation) is situational, so it lives in the `kotlin-api-review` skill rather than always-on rules. Two rules — `use-data-class` (K-3) and `nullable-question-mark` (K-2) — carry a one-line public-API exception that hands off to that skill, because their always-on advice can break binary compatibility on a published surface.

#### Stack rules — pick Kotlin libraries on JVM, not Python or legacy Java

| ID | Rule | Summary |
|----|------|---------|
| S-1 | [kotlin-stack-defaults](rules/kotlin-stack-defaults.md) | Kotlin 2.3, JDK 21, Gradle Kotlin DSL — these are the defaults, not optional |
| S-2 | [coroutines-for-concurrency](rules/coroutines-for-concurrency.md) | kotlinx-coroutines over `Thread`/`Future`/RxJava; `Dispatchers.IO` for I/O, `Default` for CPU |
| S-3 | [ktor-for-http](rules/ktor-for-http.md) | Ktor client + server (CIO engine) for HTTP — not OkHttp, not `java.net.HttpURLConnection` |
| S-4 | [djl-for-jvm-ml](rules/djl-for-jvm-ml.md) | DJL for on-JVM ML inference — not Python subprocesses, not hand-rolled JNI |
| S-5 | [javacv-for-vision](rules/javacv-for-vision.md) | JavaCV (OpenCV bindings) for camera + image processing — not `cv2` via subprocess |
| S-6 | [koog-for-agents](rules/koog-for-agents.md) | Koog for AI agent orchestration on JVM — not Python-only frameworks |

### Skills (4)

| Skill | Description |
|-------|-------------|
| [kotlinify-tests](skills/kotlinify-tests/SKILL.md) | Convert JUnit-style test classes to idiomatic Kotest specs; delegates to `verify-no-junit-assertions.sh` for the deterministic gate |
| [pojoify-to-dataclass](skills/pojoify-to-dataclass/SKILL.md) | Refactor a Java-style POJO (manual equals/hashCode/toString, bean accessors) into an idiomatic Kotlin `data class` |
| [nullable-cleanup](skills/nullable-cleanup/SKILL.md) | Replace `Optional<T>` with `T?` and the `?.` / `?:` / `?.let { }` operators |
| [kotlin-api-review](skills/kotlin-api-review/SKILL.md) | Review an API you're designing or exposing — function, class, or published library — for design concerns: simplicity, readability, consistency, predictability, debuggability, testability, plus (published surfaces only) backward compatibility, multiplatform, documentation |

### Scripts (1)

| Script | Purpose |
|--------|---------|
| [verify-no-junit-assertions.sh](scripts/verify-no-junit-assertions.sh) | Fails CI if any test file under `src/test/` still uses JUnit-style assertions. Delegated from the `kotlinify-tests` skill; runs standalone as a CI gate. Enforces rule K-4. |

### Eval Scenarios

| Scenario | Targets skill | Purpose |
|----------|---------------|---------|
| scenario-0, scenario-1, scenario-2 | kotlinify-tests | Real evals (auto-generated, curated to remove bleeding) |
| scenario-pojoify-subscription | pojoify-to-dataclass | Java-style POJO → idiomatic data class refactor |
| scenario-nullable-user-service | nullable-cleanup | `Optional<T>` API → Kotlin nullable types, caller updated alongside |
| trial-1-task-leaks-language | — | **Pedagogical** — slide-59 demo, task leaks language, baseline 100% |
| trial-2-rubric-grades-features | — | **Pedagogical** — slide-59 demo, rubric still grades features only, baseline still 100% |
| trial-3-rubric-weights-language | — | **Pedagogical** — slide-59 demo, rubric weights idiomatic Kotlin 80%, lift becomes measurable |

The pedagogical scenarios are intentionally bad eval design — they ship publicly so audiences of the talk "You're Absolutely Right (and Other Lies My AI Told Me)" can browse them in the registry and see why the failures land at 100% / 100% / ~20%-then-~95%.

## The Skill / Script Delegation Pattern

The `kotlinify-tests` skill demonstrates the script-delegation pattern in miniature: the skill does the judgment work (which Kotest matcher fits this JUnit call? what spec style preserves the original structure?), and `verify-no-junit-assertions.sh` does the deterministic gate (did any JUnit assertion slip through?). Judgment lives in the skill; mechanism lives in the shell. The script also runs when the agent isn't present — human commits go through the same gate in CI.

## Conventions

- All thirteen rules apply per-turn (frontmatter `alwaysApply: true`); agents see them on every prompt
- Skills are intent-discovered via the trigger phrases in their `description` field
- Rule IDs (`K-1` through `K-7`, `S-1` through `S-6`) are talk-shorthand for the descriptive filenames; the registry uses the filenames

See [CHANGELOG.md](CHANGELOG.md) for version history.
