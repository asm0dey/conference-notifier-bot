---
name: kotlinify-tests
description: >
  Convert JUnit-style test classes (`@Test` methods, `assertEquals`,
  `assertThrows`) to idiomatic Kotest specs (`DescribeSpec` / `BehaviorSpec`,
  `shouldBe`, `shouldThrow`). Maps assertions, rewrites imports, preserves
  test intent, then verifies the conversion by delegating to
  `scripts/verify-no-junit-assertions.sh`. Use when the user wants to migrate
  a JUnit test file to Kotest, when a test file mixes JUnit and Kotest
  assertions, or when a new contributor writes JUnit-style assertions in a
  Kotest project.
---

# Kotlinify Tests

Process steps in order. Do not skip ahead.

## Step 1 — Locate The Target Files

- If the user named specific files, use those
- Otherwise glob `src/test/**/*.kt` and identify files containing JUnit imports (`org.junit.jupiter.api.*`, `org.junit.Assert.*`) or assertion calls (`assertEquals`, `assertTrue`, `assertFalse`, `assertNull`, `assertNotNull`, `assertThrows`)
- Report the list of files you plan to convert; if more than 10, batch them and confirm before proceeding

## Step 2 — Read Each File And Plan The Conversion

For each file:

- Identify the test class structure (`@Test` methods inside a class, or top-level functions)
- Map JUnit assertions to Kotest equivalents:
  - `assertEquals(expected, actual)` → `actual shouldBe expected`
  - `assertTrue(condition)` → `condition.shouldBeTrue()` or `condition shouldBe true`
  - `assertFalse(condition)` → `condition.shouldBeFalse()` or `condition shouldBe false`
  - `assertNull(value)` → `value.shouldBeNull()`
  - `assertNotNull(value)` → `value.shouldNotBeNull()`
  - `assertThrows<E> { … }` → `shouldThrow<E> { … }`
  - `assertEquals(expected, actual, "msg")` → `withClue("msg") { actual shouldBe expected }`
- Decide on the spec style:
  - **`DescribeSpec`** when the existing tests are grouped by behavior (`describe("when X") { it("does Y") { … } }`)
  - **`BehaviorSpec`** when the existing tests follow given/when/then naming
  - **`StringSpec`** when tests are flat and self-describing — simplest mechanical conversion
- Default to `StringSpec` for mechanical conversions; only escalate to `DescribeSpec` / `BehaviorSpec` when there's real grouping to preserve

## Step 3 — Rewrite The Test Class

- Change the class declaration: `class FooTest` becomes `class FooTest : StringSpec({ … })` (or the chosen spec)
- Move each `@Test fun something()` into a string block: `"something" { … }`
- Remove `@Test`, `@BeforeEach`, `@AfterEach`, `@Disabled` annotations — replace lifecycle hooks with Kotest's `beforeEach { … }` / `afterEach { … }` / `extension` registrations inside the spec body
- Apply the assertion mapping from Step 2

## Step 4 — Update Imports

- Remove: `org.junit.jupiter.api.*`, `org.junit.Assert.*`, `kotlin.test.*` (if mixed)
- Add: `io.kotest.core.spec.style.<SpecStyle>` for the chosen spec, `io.kotest.matchers.shouldBe`, `io.kotest.matchers.shouldNotBe`, `io.kotest.assertions.throwables.shouldThrow`, and any matcher-specific imports your assertions need (`io.kotest.matchers.nulls.*`, `io.kotest.matchers.booleans.*`)
- Sort imports alphabetically per project convention

## Step 5 — Verify Compile

- Run the project's compile command (`./gradlew testClasses` or equivalent)
- If compilation fails, fix the conversion errors — common ones: missed `import`, wrong matcher (e.g., `shouldBe` instead of `shouldContain` for a collection check), spec style mismatch
- Do not proceed to Step 6 until the project compiles cleanly

## Step 6 — Run The Verification Script

- Execute: `scripts/verify-no-junit-assertions.sh`
- Exit code 0 means no JUnit assertions remain — conversion is complete
- Exit code 1 means at least one JUnit assertion slipped through — the script's stderr output names the file:line offenders
- Fix the offending lines (this is judgment: the script catches the leak, you decide the matcher) and re-run the script
- The script is the deterministic gate. Do not declare the skill complete until the script exits 0

Finish here. Do not commit or push — that's the operator's call.
