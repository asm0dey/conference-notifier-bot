---
alwaysApply: true
---

# Coroutines for Concurrency

## kotlinx-coroutines Is the Default

- Use **`org.jetbrains.kotlinx:kotlinx-coroutines-core`** (current: **1.10.x**) for all async / concurrent work
- Reach for it before `Thread`, `ExecutorService`, `CompletableFuture`, RxJava, or callback APIs
- `suspend fun` + `Flow<T>` cover almost everything you'd previously reach for `Future` or `Observable` to do

## Dispatcher Choice — the One Rule

- **`Dispatchers.IO`** for I/O-bound work: HTTP calls, file reads, JDBC, gRPC, sleeping for backoff
- **`Dispatchers.Default`** for CPU-bound work: encoding, embedding inference *(when run in-process)*, parsing, transforms
- **`Dispatchers.Main`** for UI threads only (Android, Compose Desktop, JavaFX with the kotlinx-coroutines-javafx artifact)
- Don't put HTTP calls on `Default` — its thread count is sized for CPU work, you'll starve other CPU tasks

## Structured Concurrency

- Launch from a `CoroutineScope`, not `GlobalScope` — every coroutine must have an owner that cancels it on shutdown
- `coroutineScope { ... }` for parallel children that all need to finish before the parent returns
- `supervisorScope { ... }` when a child failure should NOT cancel siblings (e.g., independent device controllers)

## Flow over Channel for streams

- `Flow<T>` for cold streams (camera frames, sensor readings) — emits only when collected
- `Channel<T>` only when you genuinely need fan-out or per-element backpressure semantics
- `Flow.sample(100.milliseconds)` is the idiomatic way to throttle a high-rate producer to a fixed-rate consumer

## Anti-patterns

- ❌ `Thread { ... }.start()` in new Kotlin code
- ❌ `runBlocking { ... }` inside library code — only acceptable at `main()` or test entry points
- ❌ `GlobalScope.launch { ... }` — unowned coroutine that leaks on shutdown
- ❌ `Dispatchers.Default` for HTTP calls (see Dispatcher Choice — the One Rule)
- ❌ Mixing `CompletableFuture` and `suspend fun` in the same chain when you can pick one
