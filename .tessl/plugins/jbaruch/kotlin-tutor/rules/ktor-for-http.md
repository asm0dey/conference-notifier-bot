---
alwaysApply: true
---

# Ktor for HTTP

## Use Ktor for Both Client and Server

- HTTP **client** work: **`io.ktor:ktor-client-core` + `ktor-client-cio`** (CIO is the coroutine-native engine; pick it unless you have a specific reason to use Apache or OkHttp)
- HTTP **server** work: **`io.ktor:ktor-server-core` + `ktor-server-cio`** for embedded servers; **`ktor-server-netty`** when you need Netty's tuning surface
- Both run on coroutines. `client.get(...)` / `client.post(...)` are `suspend` functions. They compose with `Flow` and `withContext(Dispatchers.IO)` naturally.

## Versions

- Use **Ktor 3.4.x** (current). Stick to a single Ktor version across client + server in the same module — mixed 2.x / 3.x will not link cleanly.
- Ktor 3.4 depends on kotlinx-coroutines 1.10.2; align if you're pinning coroutines.

## Idioms

- Configure the client once at the application top level; pass it where needed. Per-call `HttpClient(CIO)` allocation leaks under load.
- Use `parameter("key", value)` instead of string-concatenating the URL — type-safe and URL-encoded for free.
- Close the client on shutdown: `Runtime.getRuntime().addShutdownHook(Thread { client.close() })`.
- For JSON, install `ContentNegotiation` + `kotlinx-serialization-json` rather than hand-parsing response bodies.

## Anti-patterns

- ❌ `java.net.HttpURLConnection` or `URL("…").openStream()` in new code — pre-coroutine, no async, painful headers
- ❌ OkHttp + Retrofit when there's no Android constraint — adds an Android-shaped abstraction layer on top of a JVM project
- ❌ `requests`-style synchronous Apache HttpClient in a coroutine context — blocks the dispatcher thread
- ❌ Two HTTP clients in the same app for no reason (legacy `RestTemplate` + new Ktor side-by-side)
