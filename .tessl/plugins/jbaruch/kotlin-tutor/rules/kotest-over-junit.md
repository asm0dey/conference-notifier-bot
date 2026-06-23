---
alwaysApply: true
---

# Kotest Over JUnit

## Assertions Use Kotest Matchers

- Test assertions use Kotest's `shouldBe` / `shouldNotBe` / `shouldThrow` / `shouldContain` style — not JUnit's `assertEquals` / `assertTrue` / `assertThrows`
- Kotest matchers compose, read left-to-right (`actual.shouldBe(expected)`), and produce richer failure messages by default
- `assertEquals(expected, actual)` reads backwards relative to the prose it lives in; `actual shouldBe expected` doesn't

## Test Structure Uses Kotest Specs

- New test classes extend a Kotest spec — `DescribeSpec`, `BehaviorSpec`, `StringSpec` — not `@Test` methods on a JUnit class
- The spec style lets you nest contexts and share setup naturally; the JUnit class style fights against that

## JUnit Runner Is Fine

- Kotest tests run on the JUnit 5 platform via the Kotest JUnit runner — your existing Gradle `test` task picks them up with no changes
- This isn't a build-system migration; it's an assertion-and-structure migration
- Mixed projects (some classes JUnit, some Kotest) compile and run together; new tests go Kotest, old tests can be converted via the `kotlinify-tests` skill
