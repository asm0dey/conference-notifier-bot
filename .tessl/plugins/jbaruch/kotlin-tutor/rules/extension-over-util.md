---
alwaysApply: true
---

# Extension Functions Over Utility Classes

## Add Behaviour Where It Belongs

- A static utility method `StringUtils.normalize(s: String): String` becomes an extension `fun String.normalize(): String`
- Callers read `s.normalize()` instead of `StringUtils.normalize(s)` — the operation lives where the type lives
- Extensions are resolved statically, so there's no runtime cost — they're a readability and discoverability win, nothing else

## When To Reach For Extensions

- You'd otherwise create a `*Utils` / `*Helper` / `*Manager` class with static methods
- You need to add a derived getter or convenience method to a type you don't own (`Instant`, `String`, `List<T>`)
- You want a domain-specific operation on a primitive — `42.minutes`, `"foo".toSlug()` — without polluting the type globally

## When Not To

- Don't add an extension that hides side effects — extensions should look like properties or pure functions of the receiver
- Don't add an extension on a type you DO own — just put the method on the class. Extensions on your own types signal "this should have been a method"
- Top-level utility files (`StringExtensions.kt`) are fine; one giant `Utils.kt` collecting every extension is not — group by receiver type
