---
name: pojoify-to-dataclass
description: >
  Refactor a regular Kotlin `class` with hand-written `equals`, `hashCode`,
  `toString`, and Java-bean accessors (getX/setX) into an idiomatic Kotlin
  `data class` with `val` properties, nullable `?` types, and compiler-
  generated equality. Removes the boilerplate that ports from Java leave
  behind. Use when the user asks to "make this a data class," "convert this
  POJO," "kotlinify this model," or shows a class with all three of equals /
  hashCode / toString manually overridden.
---

# Pojoify To Data Class

Process steps in order. Do not skip ahead.

## Before / After Example

**Before (Java-style POJO):**
```kotlin
class Customer {
    var customerId: String? = null
    var email: String? = null

    fun getCustomerId(): String? = customerId
    fun setCustomerId(value: String?) { customerId = value }
    fun getEmail(): String? = email
    fun setEmail(value: String?) { email = value }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Customer) return false
        return customerId == other.customerId && email == other.email
    }
    override fun hashCode(): Int = 31 * (customerId?.hashCode() ?: 0) + (email?.hashCode() ?: 0)
    override fun toString(): String = "Customer(customerId=$customerId, email=$email)"
}
```

**After (idiomatic Kotlin):**
```kotlin
data class Customer(
    val customerId: String? = null,
    val email: String? = null
)
```

## Step 1 — Verify The Class Is A Value Type

A class is a candidate when it has all of:

- Fields that look like data (no behaviour beyond getters and setters)
- Manually-overridden `equals`, `hashCode`, AND `toString` — all three
- No inheritance from a non-data class with state

If any of these are missing, stop and report what you found. Not every class with `equals`/`hashCode` is a data class candidate — some have legitimate inheritance or behaviour.

### Public-API caveat

A `data class` is the wrong choice on a **published API**: the generated constructor, `copy()`, and `componentN()` signatures change when properties are added or reordered, breaking binary and behavioural compatibility. This is the public-API exception called out in the `use-data-class` rule.

So before converting, check whether the type is part of a stable public/binary API (library surface, multiplatform module, anything other consumers depend on). If it is, flag it and prefer keeping a regular class — or convert only with the operator's explicit OK and a compatibility plan; the `kotlin-api-review` skill covers the full public-API checklist. Internal / application-only types are safe to convert.

## Step 2 — Capture The Field Order

- Read the existing class and list the fields in the order they appear
- The `data class` primary constructor parameters MUST appear in the same order — otherwise existing `componentN()` destructuring breaks for callers
- If the existing class has both public fields and Java-bean accessors, the accessor naming tells you the canonical field name (`getCustomerId` → `customerId`)

## Step 3 — Rewrite The Class Declaration

- Change `class Foo { val a: String; val b: Int; … }` to `data class Foo(val a: String, val b: Int)`
- Convert any `var` to `val` UNLESS the field has a documented reason to mutate (flag any retained `var` in the conversion summary)
- Move computed properties (`val derived: T get() = …`) into the class body, after the primary constructor
- If a field had a custom getter that did real work (validation, lazy init), preserve it in the class body and flag for human review — data class generated equality reads the constructor-declared property

## Step 4 — Remove The Manually-Overridden Methods

- Delete `equals`, `hashCode`, and `toString` — the compiler generates them now
- If `toString` was overridden to MASK a sensitive field (passwords, tokens), keep ONLY `toString` as a manual override and delete `equals` and `hashCode`
- Remove `init` blocks that only validated equality contract assumptions
- Remove Java-bean getX / setX methods if they exist

## Step 5 — Update Callers If Needed

- Glob the codebase for callers using setX methods on this type — those won't compile anymore
- Replace `foo.setX(value)` with `foo.copy(x = value)` — the idiomatic mutation pattern from rule `use-data-class`
- Positional construction (`Foo(a, b)`) keeps working if Step 2 preserved field order

## Step 6 — Run The Tests

- Run the project's test suite
- If any test fails, the conversion broke a contract — common causes: field order change, removed validation logic, caller using setter
- Fix and re-run; do not declare complete with red tests

Finish here. Do not commit — that's the operator's call.
