---
alwaysApply: true
---

# Kotlin Stack Defaults

## Language and Runtime

- **Kotlin 2.3.x** (`kotlin("jvm") version "2.3.0"`) — the latest stable, picks up the type-inference and coroutine fixes that downstream libraries (Koog, Ktor 3.4, kotlinx-coroutines 1.10) build against
- **JDK 21** (`jvmToolchain(21)`) — the current LTS; required by JavaCV 1.5.13's native libs and recommended by Koog
- Avoid Kotlin 2.1.x for new projects — Koog ≥ 0.7 (including the 1.0.0-preview line) artifacts are built with newer Kotlin metadata that 2.1.x consumers can't compile against

## Build System

- **Gradle with Kotlin DSL** (`build.gradle.kts`, `settings.gradle.kts`) — Groovy DSL only for legacy projects you didn't start
- Use the **Gradle wrapper** (`./gradlew`) committed to the repo so a fresh clone runs without a system Gradle install
- Prefer **version catalogs** (`gradle/libs.versions.toml`) when there are more than ~5 dependencies; inline `implementation("group:artifact:version")` is fine for tiny projects

## Project Structure

- Standard layout: `src/main/kotlin/`, `src/main/resources/`, `src/test/kotlin/`
- One `main()` per file when each file is independently runnable (CLI tools, demo stages); use `application` plugin + per-stage `JavaExec` tasks rather than a single fat main

## Anti-patterns

- ❌ Java + Kotlin mixed module without a clear reason — pick one, write Kotlin
- ❌ Maven instead of Gradle for new Kotlin projects
- ❌ JDK 17 / 11 / 8 without a forcing constraint — modern Kotlin stacks assume 21
- ❌ `apply plugin: 'kotlin'` in a `build.gradle` Groovy file when starting fresh
