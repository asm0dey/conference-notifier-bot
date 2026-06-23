---
alwaysApply: true
---

# StateFlow Over AtomicReference + Polling

## The Anti-Pattern

- A coroutine that busy-polls an `AtomicReference<T?>` for new values — `while (true) { val x = ref.get(); if (x == null) { delay(20); continue }; ... }` — is Java-in-Kotlin
- `j.u.c.atomic` is a lock-free primitive sized for CAS-heavy contention; the busy-poll loop is asking it to do single-writer / many-reader fan-out, which is the wrong shape
- The `delay`-based poll loop also burns timer ticks and adds latency proportional to the poll interval, neither of which a reactive signal should carry

## The Idiomatic Replacement

- For single-writer, many-reader state that consumers want to react to, use **`MutableStateFlow<T?>`** (`kotlinx.coroutines.flow.MutableStateFlow`) initialized to `null` when `null` means "no value yet" — the 1:1 replacement for `AtomicReference<T?>`
- When the state always has a value (a sensible default exists), use `MutableStateFlow<T>` with that default and drop the `filterNotNull()` from the reader
- Reader side for the nullable variant: `latest.filterNotNull().collect { ... }` — suspends until the next non-null value, zero CPU when idle, no polling loop
- Reader side for the non-null variant: `latest.collect { ... }` — same suspend semantics, no `filterNotNull()` needed
- Writer side: `latest.value = newValue` — reads naturally, no `.set(...)` / `.get()` ceremony
- Conflation is built in: a slow consumer naturally drops intermediate values, which live signals (video frames, sensor readings, status snapshots) want

## When `AtomicReference` Is Still Right

- You genuinely need lock-free CAS semantics — `compareAndSet`, `getAndUpdate`, etc. — to coordinate across non-coroutine threads
- No consumer wants to react to value changes; the reference is read on demand only, never waited on

## The Trigger Smell

- If you write or see `while (true) { val x = ref.get(); if (x == null) delay(...); continue }`, replace the atomic with `MutableStateFlow` and the loop with `.collect`
- Same trigger applies to any `do { ... } while (state == ...)` busy-poll guarding against an "is the value ready yet?" condition — that's a `Flow` or a `Channel.receive()`, not an atomic and a sleep
- See `rules/coroutines-for-concurrency.md`

## Anti-patterns

- ❌ `AtomicReference<T?>` + polling `delay` loop as a state-change signal
- ❌ Long-running `while (true) { delay(...) }` reader in a coroutine — coroutines are *built* to avoid this
- ❌ `@Volatile var` + polling — same pattern, different decoration
