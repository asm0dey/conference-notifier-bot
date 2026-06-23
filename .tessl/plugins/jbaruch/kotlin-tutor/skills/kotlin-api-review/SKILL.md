---
name: kotlin-api-review
description: >
  Review the surface of a Kotlin API you're designing or exposing — a
  function, a class, a module, or a published library — against the concerns
  that govern good API design: simplicity, readability, consistency,
  predictability, debuggability, testability, and (for published surfaces)
  backward compatibility, multiplatform, and documentation. Use when the user
  is designing or reviewing an API — phrases like "review this API," "is this
  API idiomatic," "design this interface," "review my public API," "will this
  break binary compatibility," "API design check," or when changing a type
  that other modules or external consumers depend on. This is the
  design-and-review counterpart to the always-on idiom rules: those govern how
  to write a line of Kotlin, this governs how to shape and expose an API.
---

# Kotlin API Review

Reviews an API surface — anything from a single public function to a published library — for the design concerns that the always-on idiom rules (`use-data-class`, `nullable-question-mark`, `extension-over-util`, …) don't cover. This guidance is here, not in always-on rules, because it's situational: it earns its keep when you're shaping an API, not on every line of code.

Process steps in order. Do not skip ahead. Step 0 selects which later steps apply: Step 1 runs only for a published / binary-stable surface and Step 8 only for a Kotlin Multiplatform library — the gates are not optional-by-default, Step 0 decides them. Steps 2–7, 9, and 10 always apply. Unless a step says otherwise, continue immediately to the next.

## Step 0 — Identify The Surface And Its Exposure

- Identify what's exposed and to whom: `public` declarations, `@PublishedApi` (inlined into consumer bytecode — treat as public), multiplatform `expect`/`actual`, and who depends on it (same module, other modules, external consumers)
- **Exposure level decides which steps apply.** Steps 2–7 (simplicity, predictability, readability, consistency, debuggability, testability) apply to any API surface. Step 1 (backward compatibility) applies only to a **published / binary-stable** surface; Step 8 (multiplatform) only to a **Kotlin Multiplatform** library
- Note the level up front. Then proceed to Step 1 if the surface is published / binary-stable; otherwise proceed directly to Step 2

## Step 1 — Backward Compatibility (published surface only — the highest-stakes checks)

Skip for internal / application code. For a published, binary-stable surface, first know which kind of compatibility a finding breaks — name it in the report:

- **Binary** — already-compiled client code keeps linking against the new version (no `NoSuchMethodError`). Hardest to preserve, most important for a published library
- **Source** — client code recompiles unchanged against the new version. Desirable but an aspiration, not a promise
- **Behavioral** — same features, same semantics; new version only fixes bugs
- Binary and source compat are independent — one can break while the other holds

Flag, per declaration:

- **Implicit return types** on public functions/properties — refactoring can silently change them, breaking binary compat. Require explicit return types. Recommend turning on **Explicit API mode** (`explicitApi()` in the Kotlin compiler options), which makes the compiler enforce explicit return types and visibility modifiers across the public surface
- **Default arguments** on public functions — a Kotlin call against a default compiles to the synthetic `$default` method, whose signature carries the full parameter list plus a flags mask; add or reorder a parameter and that synthetic's signature shifts, so stale Kotlin client bytecode breaks. `@JvmOverloads` doesn't fix this — it generates Java-visible overloads that keep *Java* callers binary-compatible, but it leaves the Kotlin `$default` mechanism untouched, so Kotlin callers still break. Prefer explicit manual overloads
- **`data class` in public API** — generated constructor / `copy()` / `componentN()` signatures shift when a property is added or reordered. Prefer a regular class for stable surface (this is the public-API exception to the `use-data-class` rule)
- **Return-type widening/narrowing** — widening (`List` → `Collection`) breaks callers that index; narrowing (`Collection` → `List`, `Number` → `Int`, `Optional<T>` → `T?`) keeps source compat but breaks binary compat. Route through a deprecation cycle, not an in-place swap (the public-API exception to `nullable-question-mark`)
- **New required (non-default) parameters** on existing functions — breaks both binary and source compat
- **`@PublishedApi` declarations** — `internal` members marked `@PublishedApi` get inlined into client bytecode (the mechanism that lets a public `inline` function call them, since inline public functions can't reference plain non-public declarations). They are effectively public — apply every rule above to them
- **Missing deprecation cycle** — breaking changes should go Warning → Error → Hidden → Removal (removal only in a major release), with `@Deprecated(message, replaceWith, level)`; communicate the versioning/deprecation policy to users
- Recommend the [Binary Compatibility Validator](https://github.com/Kotlin/binary-compatibility-validator) (`apiDump` / `apiCheck`, commit the `.api` file; built into the Kotlin Gradle plugin 2.2.0+) if not already wired in
- Recommend `@RequiresOptIn` markers (Preview/Experimental/Delicate) for unstable surface, each category documented in KDoc; **propagate** the marker when the library itself consumes an experimental API from a dependency. Do NOT use opt-in to deprecate — that's `@Deprecated`'s job

Proceed immediately to Step 2.

## Step 2 — Simplicity

- **Minimize the number of components** — fewer types, functions, and parameters mean less to learn; don't expose a type or option that earns its keep only in a rare case
- **Reuse existing Kotlin types** — accept and return stdlib types (`List`, `Map`, `Duration`, `Result`, `Sequence`) instead of inventing wrappers that consumers must learn and convert to
- **Build on core abstractions** — define a small set of core concepts and layer additional functionality on top (extensions, composition) rather than a wide flat surface of unrelated entry points

## Step 3 — Predictability

- **Do the right thing by default** — the happy path should work with minimal config; supply sensible defaults
- **Allow extension** where the right choice can't be predetermined (extension functions/properties, pluggable strategies)
- **Prevent invalid extension** — `sealed` types over `open` when only specific implementations are valid; enables exhaustive `when` with no `else`, so a new subtype turns every unhandled branch into a compile error
- **No exposed mutable state** — return read-only `List` / `Set` / `Map`, never the live mutable internal; return `.toList()` when callers need a stable snapshot; keep `Array` out of public signatures (`vararg` + spread already defensively copies)
- **Validate inputs/state** — `require()` for arguments (`IllegalArgumentException`), `check()` for instance state (`IllegalStateException`); put the offending value in the message, never sensitive data

## Step 4 — Readability

- **Compose over parameters** — `flow.filter().map().buffer()` beats one function with `filter`/`map`/`buffer` flags
- **DSLs for configuration** — trailing lambda-with-receiver for builder-style config
- **Extensions for layered behaviour** — only core behaviour, operators, and overrides as members; everything else as extensions (mirrors the `extension-over-util` rule)
- **No boolean arguments** — `doWork(true)` is unreadable at the call site; split into named functions (`map` / `mapNotNull`), use an `enum` for three-plus modes, or at least require the flag be passed as a named argument (`overwrite = true`)
- **Right numeric type** — `Int`/`Long`/`Double` for arithmetic, `Byte`/`Short`/`Float` for storage constraints, unsigned types for full-positive/interop, inline value classes for IDs and other non-arithmetic entities

## Step 5 — Consistency

- Stable parameter order and naming across similar functions; general-to-specific (essential first, optional last)
- One term per concept throughout (`element` vs `item` vs `entry` — pick one)
- Overloads with different parameter types must be semantically identical (`BigDecimal(200)` == `BigDecimal("200")`)
- Predictable name patterns: `OrNull` suffix → nullable return; `Catching` suffix → exception-wrapping
- One error-handling mechanism across the API (exceptions vs nullable vs `Result<T>`); don't use exceptions for normal control flow

## Step 6 — Debuggability

- Meaningful `toString()` on every stateful type (including internal ones) — no sensitive data, consistent format; document the format only if it's part of the contract. (Note: don't reach for `data class` just to get `toString()` on public API — see Step 1)
- Documented, consistent exception policy: rethrow unchanged when a dependency is intentionally exposed; wrap in a library-specific exception (original via `cause`) when the dependency is an implementation detail

## Step 7 — Testability

- The library AND the code that consumes it must be easy to test
- Provide test doubles (fakes/in-memory implementations) for any type a consumer can't construct or stub themselves — backed by a `sealed`/`interface` seam, not a `final` concrete class
- Don't force a real I/O dependency (network, clock, filesystem) into the only public entry point; allow injection so consumers can test without it
- If you can't document how to test against the API without a live data source, that's a design smell — fix the surface, not the docs

Proceed to Step 8 only if this is a Kotlin Multiplatform library; otherwise skip directly to Step 9.

## Step 8 — Multiplatform (only if this is a Kotlin Multiplatform library)

Skip entirely for JVM-only libraries. When the library targets KMP:

- **Place each API in the broadest source set it can live in** — `commonMain` first, then an intermediate set (`concurrent`, `nonJvm`), then a platform set (`androidMain`) only for genuinely platform-exclusive APIs
- **Design for common code** — usable from `commonMain` without platform-specific glue; sensible defaults, platform options only where needed
- **Consistent behaviour across platforms** — same valid inputs, same actions, same results everywhere; isolate divergence behind `expect`/`actual` and document any unavoidable platform difference
- **Maximize target support** — cross-compile (e.g. `.klib` for Apple targets without an Apple machine), tier Kotlin/Native targets
- **Test on every supported platform** — `kotlin-test` for common tests plus the platform runners
- **Non-Kotlin interop** — design types for clean Swift/native access where that's a goal; discoverable via klibs.io

Proceed to Step 9.

## Step 9 — Documentation

- KDoc on every public entry point — describe behaviour, valid input ranges, behaviour on invalid input, and every exception thrown; restating the signature is not documentation
- Document lambda parameters' exception and concurrency semantics (which thread? parallel? rethrow or wrap?)
- Document by example (inline KDoc snippets), link related APIs (`@see`, mirrored `format`/`parse`)
- Self-contained, simple English; keep the public surface minimal, and hide a declaration you can't make non-public from the generated docs with the `@suppress` KDoc tag

Proceed to Step 10.

## Step 10 — Report

- One finding per line: `file:line — <category>: <problem>. <fix>.`
- Lead with Step 1 (backward-compat) findings — those are the ones that bite consumers hardest
- Do not auto-apply breaking changes. Propose the deprecation-cycle path and let the operator decide. Non-breaking fixes (add explicit return type, add KDoc, `require()` a precondition) can be applied directly
- Finish here.
