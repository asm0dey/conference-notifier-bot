---
alwaysApply: true
---

# Prefer Stdlib Scope Functions

## Use `.let` / `.also` / `.apply` / `.run` / `.with`

- Kotlin's scope functions replace whole categories of imperative Java patterns — null-check-then-do, init-then-use, conditional-side-effect
- `value?.let { … }` runs the block only when present, scoped to the value as `it`
- `builder.apply { setX(…); setY(…) }` configures and returns the builder; no intermediate variable
- `result.also { log.info("got $it") }` runs a side effect without breaking the chain
- `with(receiver) { … }` and `run { … }` are situational; reach for `let` / `apply` / `also` first

## The Java Patterns They Replace

- `if (x != null) x.foo() else null` becomes `x?.let { it.foo() }` — but at that point just write `x?.foo()`
- The `?.let { … }` pattern shines when the block is more than one call: `x?.let { transform(it); return persist(it) }`
- Method chains that need a side effect mid-way become `chain.also { sideEffect(it) }.next()`

## Keep It Readable

- Scope functions are a readability tool, not a code-golf one. If the block is more than a few lines, extract a function and call it the boring way
- Nested scope functions (`.let { it.let { … } }`) are a smell — extract the inner block to a named function
- `it` is the default name for the scope receiver; when nesting or when the scope is non-trivial, rename to a descriptive parameter
