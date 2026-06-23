# Java CFP Telegram Bot Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Telegram bot that watches `https://javaconferences.org/conferences.json` and notifies registered chats (your DM and/or a channel) when a conference CFP first appears open, then daily during the final week before it closes.

**Architecture:** A single JVM process runs two things against a shared embedded H2 database: (1) the vendelieu telegram-bot long-polling listener handling a `/start` command that persists the sender's chat id, and (2) a db-scheduler recurring daily task that fetches the conference feed, computes which reminders are due from persisted state, and sends them to every registered chat. All "what was already sent" lives in the DB, so the daily run is a pure recompute — restarts never reset progress, and a missed day self-heals on the next run (db-scheduler also runs the missed execution on startup).

**Tech Stack:** Kotlin 2.3 / JDK 21, Gradle Kotlin DSL + version catalog, KSP, vendelieu telegram-bot 9.5.0, Ktor client (CIO), kotlinx-serialization-json, db-scheduler + H2 (HikariCP), Kotest.

## Global Constraints

- **Kotlin 2.3.0**, `jvmToolchain(21)` — every module.
- **Gradle Kotlin DSL** with the **Gradle wrapper** committed; dependencies via **version catalog** `gradle/libs.versions.toml`.
- **KSP plugin `2.3.8`** — required by telegram-bot's annotation processor (`ktnip`).
- Source of truth feed: `https://javaconferences.org/conferences.json` (top-level JSON array; records carry only `cfpEndDate`, no open date).
- **CFP-open semantics:** "first-seen-open" — the first time a conference appears with a parseable `cfpEndDate` that is today or later, emit the OPENED reminder once. Persisted per `name|cfpEndDate` key so next year's CFP (new end date) re-announces.
- **Reminder window:** `7` days. From `close - 7` through `close` (inclusive), emit one CLOSING_SOON reminder per calendar day.
- Telegram messages are **plain text** (no parse mode) — Telegram auto-links URLs; avoids Markdown escaping bugs.
- Idioms (from project rules): `val` over `var`, `data class` for value types, nullable `T?` over `Optional`, kotlinx-coroutines for async, Ktor for HTTP, **Kotest** specs + matchers (`shouldBe`) for tests, extension functions over util classes.
- Package root: `cfpbot`. Source under `src/main/kotlin/cfpbot/`, tests under `src/test/kotlin/cfpbot/`.
- Pinned versions (validate against Maven Central if resolution fails): telegram-bot/ktnip `9.5.0`, ktor `3.4.0`, kotlinx-serialization-json `1.8.0`, kotlinx-coroutines `1.10.2`, db-scheduler `15.1.1`, H2 `2.3.232`, HikariCP `6.2.1`, slf4j-simple `2.0.16`, kotest `5.9.1`.

---

## File Structure

- `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `gradlew`+wrapper — build.
- `src/main/kotlin/cfpbot/Conference.kt` — `@Serializable data class Conference` + `cfpClose()` date parsing extension.
- `src/main/kotlin/cfpbot/ReminderEngine.kt` — pure core: `BotState`, `ConfState`, `Reminder`, `ReminderKind`, `confKey`, `computeReminders`, `render()`. No I/O. The heart; most tests live here.
- `src/main/kotlin/cfpbot/ConferenceSource.kt` — Ktor fetch + deserialize of the feed.
- `src/main/kotlin/cfpbot/Db.kt` — `createDataSource`, `runDdl`, `StateRepository` (load/save reminder state, register chats).
- `src/main/kotlin/cfpbot/Notifier.kt` — `Notifier` fun-interface + `TelegramNotifier` (sends via bot).
- `src/main/kotlin/cfpbot/CheckTask.kt` — orchestration: source → engine → dispatch → persist. Injectable for tests.
- `src/main/kotlin/cfpbot/Commands.kt` — `@CommandHandler(["/start"])` registers the chat; `Registry` singleton holding the repo.
- `src/main/kotlin/cfpbot/Scheduler.kt` — db-scheduler recurring daily task wiring.
- `src/main/kotlin/cfpbot/Main.kt` — entrypoint wiring everything + `bot.handleUpdates()`.
- Tests: `ConferenceTest.kt`, `ReminderEngineTest.kt`, `ConferenceSourceTest.kt`, `StateRepositoryTest.kt`, `CheckTaskTest.kt`.

---

### Task 1: Project skeleton that builds and runs tests

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle/libs.versions.toml`
- Create: `src/main/kotlin/cfpbot/Main.kt` (placeholder)
- Test: `src/test/kotlin/cfpbot/SmokeTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: a buildable Gradle project; `./gradlew test` runs Kotest specs; `./gradlew run` executes `cfpbot.MainKt`.

- [ ] **Step 1: Write the version catalog**

Create `gradle/libs.versions.toml`:

```toml
[versions]
kotlin = "2.3.0"
ksp = "2.3.8"
telegrambot = "9.5.0"
ktor = "3.4.0"
serialization = "1.8.0"
coroutines = "1.10.2"
dbscheduler = "15.1.1"
h2 = "2.3.232"
hikari = "6.2.1"
slf4j = "2.0.16"
kotest = "5.9.1"

[libraries]
telegram-bot = { module = "eu.vendeli:telegram-bot", version.ref = "telegrambot" }
ktnip = { module = "eu.vendeli:ktnip", version.ref = "telegrambot" }
ktor-client-core = { module = "io.ktor:ktor-client-core", version.ref = "ktor" }
ktor-client-cio = { module = "io.ktor:ktor-client-cio", version.ref = "ktor" }
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "serialization" }
kotlinx-coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "coroutines" }
db-scheduler = { module = "com.github.kagkarlsson:db-scheduler", version.ref = "dbscheduler" }
h2 = { module = "com.h2database:h2", version.ref = "h2" }
hikari = { module = "com.zaxxer:HikariCP", version.ref = "hikari" }
slf4j-simple = { module = "org.slf4j:slf4j-simple", version.ref = "slf4j" }
kotest-runner = { module = "io.kotest:kotest-runner-junit5", version.ref = "kotest" }
kotest-assertions = { module = "io.kotest:kotest-assertions-core", version.ref = "kotest" }

[plugins]
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
```

- [ ] **Step 2: Write `settings.gradle.kts`**

```kotlin
rootProject.name = "conference-notifier-bot"
```

- [ ] **Step 3: Write `build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.telegram.bot)
    ksp(libs.ktnip)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.db.scheduler)
    implementation(libs.h2)
    implementation(libs.hikari)
    implementation(libs.slf4j.simple)

    testImplementation(libs.kotest.runner)
    testImplementation(libs.kotest.assertions)
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("cfpbot.MainKt")
}

tasks.test {
    useJUnitPlatform()
}
```

- [ ] **Step 4: Write placeholder `Main.kt`**

Create `src/main/kotlin/cfpbot/Main.kt`:

```kotlin
package cfpbot

fun main() {
    println("cfpbot starting…")
}
```

- [ ] **Step 5: Write the smoke test**

Create `src/test/kotlin/cfpbot/SmokeTest.kt`:

```kotlin
package cfpbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class SmokeTest : StringSpec({
    "build and test wiring works" {
        (1 + 1) shouldBe 2
    }
})
```

- [ ] **Step 6: Generate the Gradle wrapper**

Run: `gradle wrapper --gradle-version 8.14`
Expected: creates `gradlew`, `gradlew.bat`, `gradle/wrapper/`. (If no system `gradle`, install or use an existing wrapper.)

- [ ] **Step 7: Run the test to verify the toolchain resolves**

Run: `./gradlew test`
Expected: PASS — `SmokeTest` green, all dependencies resolve. If a pinned version 404s, bump it to the latest on Maven Central and note it.

- [ ] **Step 8: Commit**

```bash
git init
git add -A
git commit -m "chore: project skeleton with gradle, ksp, kotest"
```

---

### Task 2: Conference model and CFP date parsing

**Files:**
- Create: `src/main/kotlin/cfpbot/Conference.kt`
- Test: `src/test/kotlin/cfpbot/ConferenceTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `data class Conference(val name: String, val link: String = "", val locationName: String = "", val date: String = "", val cfpLink: String = "", val cfpEndDate: String = "")` — `@Serializable`.
  - `fun Conference.cfpClose(): LocalDate?` — parses `cfpEndDate` (format `d MMMM yyyy`, English) or returns `null` when blank/unparseable.

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/cfpbot/ConferenceTest.kt`:

```kotlin
package cfpbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldBeNull
import java.time.LocalDate

class ConferenceTest : StringSpec({
    "parses a single-digit day cfp end date" {
        Conference(name = "X", cfpEndDate = "3 May 2026").cfpClose() shouldBe LocalDate.of(2026, 5, 3)
    }
    "parses a two-digit day cfp end date" {
        Conference(name = "X", cfpEndDate = "30 September 2025").cfpClose() shouldBe LocalDate.of(2025, 9, 30)
    }
    "blank cfp end date yields null" {
        Conference(name = "X", cfpEndDate = "").cfpClose().shouldBeNull()
    }
    "garbage cfp end date yields null" {
        Conference(name = "X", cfpEndDate = "soon-ish").cfpClose().shouldBeNull()
    }
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "cfpbot.ConferenceTest"`
Expected: FAIL — `Conference` / `cfpClose` unresolved.

- [ ] **Step 3: Write the implementation**

Create `src/main/kotlin/cfpbot/Conference.kt`:

```kotlin
package cfpbot

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Serializable
data class Conference(
    val name: String,
    val link: String = "",
    val locationName: String = "",
    val date: String = "",
    val cfpLink: String = "",
    val cfpEndDate: String = "",
)

private val CFP_DATE_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH)

fun Conference.cfpClose(): LocalDate? =
    cfpEndDate.trim().takeIf { it.isNotEmpty() }?.let { raw ->
        runCatching { LocalDate.parse(raw, CFP_DATE_FORMAT) }.getOrNull()
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "cfpbot.ConferenceTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/cfpbot/Conference.kt src/test/kotlin/cfpbot/ConferenceTest.kt
git commit -m "feat: conference model with cfp date parsing"
```

---

### Task 3: Reminder engine (pure core)

**Files:**
- Create: `src/main/kotlin/cfpbot/ReminderEngine.kt`
- Test: `src/test/kotlin/cfpbot/ReminderEngineTest.kt`

**Interfaces:**
- Consumes: `Conference`, `Conference.cfpClose()` from Task 2.
- Produces:
  - `enum class ReminderKind { OPENED, CLOSING_SOON }`
  - `data class Reminder(val conference: Conference, val kind: ReminderKind, val daysLeft: Long)`
  - `data class ConfState(val announcedOpen: Boolean = false, val lastDailyReminder: LocalDate? = null)`
  - `data class BotState(val chats: Set<Long> = emptySet(), val confs: Map<String, ConfState> = emptyMap())`
  - `data class EngineResult(val reminders: List<Reminder>, val newState: BotState)`
  - `fun confKey(c: Conference): String`
  - `fun computeReminders(conferences: List<Conference>, state: BotState, today: LocalDate): EngineResult`
  - `fun Reminder.render(): String`
  - `const val REMINDER_WINDOW_DAYS: Long = 7`

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/cfpbot/ReminderEngineTest.kt`:

```kotlin
package cfpbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.string.shouldContain
import java.time.LocalDate

class ReminderEngineTest : StringSpec({
    val today = LocalDate.of(2026, 6, 1)
    fun conf(name: String, end: String, link: String = "https://cfp/$name") =
        Conference(name = name, cfpLink = link, cfpEndDate = end)

    "first sighting of an open cfp emits OPENED once and records it" {
        val c = conf("Devoxx", "1 August 2026") // far future, outside window
        val r = computeReminders(listOf(c), BotState(), today)
        r.reminders.map { it.kind } shouldBe listOf(ReminderKind.OPENED)
        r.newState.confs[confKey(c)]!!.announcedOpen shouldBe true
    }

    "already-announced cfp far from close emits nothing" {
        val c = conf("Devoxx", "1 August 2026")
        val state = BotState(confs = mapOf(confKey(c) to ConfState(announcedOpen = true)))
        computeReminders(listOf(c), state, today).reminders shouldBe emptyList()
    }

    "cfp closing within the window emits a daily CLOSING_SOON reminder" {
        val c = conf("KotlinConf", "5 June 2026") // 4 days out
        val state = BotState(confs = mapOf(confKey(c) to ConfState(announcedOpen = true)))
        val r = computeReminders(listOf(c), state, today)
        r.reminders.map { it.kind } shouldBe listOf(ReminderKind.CLOSING_SOON)
        r.reminders.single().daysLeft shouldBe 4L
        r.newState.confs[confKey(c)]!!.lastDailyReminder shouldBe today
    }

    "the same daily reminder is not sent twice on the same day" {
        val c = conf("KotlinConf", "5 June 2026")
        val state = BotState(
            confs = mapOf(confKey(c) to ConfState(announcedOpen = true, lastDailyReminder = today)),
        )
        computeReminders(listOf(c), state, today).reminders shouldBe emptyList()
    }

    "a reminder fires on the close day itself (daysLeft 0)" {
        val c = conf("KotlinConf", "1 June 2026")
        val state = BotState(confs = mapOf(confKey(c) to ConfState(announcedOpen = true)))
        val r = computeReminders(listOf(c), state, today)
        r.reminders.single().kind shouldBe ReminderKind.CLOSING_SOON
        r.reminders.single().daysLeft shouldBe 0L
    }

    "a brand-new cfp already inside the window emits both OPENED and CLOSING_SOON" {
        val c = conf("LastMinute", "3 June 2026")
        val r = computeReminders(listOf(c), BotState(), today)
        r.reminders.map { it.kind }
            .shouldContainExactlyInAnyOrder(ReminderKind.OPENED, ReminderKind.CLOSING_SOON)
    }

    "a cfp whose close date has passed is ignored and pruned from state" {
        val c = conf("Gone", "1 May 2026")
        val state = BotState(confs = mapOf(confKey(c) to ConfState(announcedOpen = true)))
        val r = computeReminders(listOf(c), state, today)
        r.reminders shouldBe emptyList()
        r.newState.confs.containsKey(confKey(c)) shouldBe false
    }

    "a conference with no cfp end date is skipped" {
        val c = Conference(name = "NoCfp", cfpEndDate = "")
        computeReminders(listOf(c), BotState(), today).reminders shouldBe emptyList()
    }

    "registered chats are preserved in new state" {
        val state = BotState(chats = setOf(42L, 99L))
        computeReminders(emptyList(), state, today).newState.chats shouldBe setOf(42L, 99L)
    }

    "next year's cfp (new end date) re-announces as OPENED" {
        val thisYear = conf("Devoxx", "1 August 2026")
        val nextYear = conf("Devoxx", "1 August 2027")
        val state = BotState(confs = mapOf(confKey(thisYear) to ConfState(announcedOpen = true)))
        val r = computeReminders(listOf(nextYear), state, today)
        r.reminders.map { it.kind } shouldBe listOf(ReminderKind.OPENED)
    }

    "OPENED message names the conference and its deadline" {
        val c = conf("Devoxx", "1 August 2026")
        val text = Reminder(c, ReminderKind.OPENED, 61L).render()
        text shouldContain "Devoxx"
        text shouldContain "1 August 2026"
    }

    "CLOSING_SOON message says closes today when daysLeft is 0" {
        val c = conf("KotlinConf", "1 June 2026")
        Reminder(c, ReminderKind.CLOSING_SOON, 0L).render() shouldContain "TODAY"
    }
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "cfpbot.ReminderEngineTest"`
Expected: FAIL — engine symbols unresolved.

- [ ] **Step 3: Write the implementation**

Create `src/main/kotlin/cfpbot/ReminderEngine.kt`:

```kotlin
package cfpbot

import java.time.LocalDate
import java.time.temporal.ChronoUnit

const val REMINDER_WINDOW_DAYS: Long = 7

enum class ReminderKind { OPENED, CLOSING_SOON }

data class Reminder(val conference: Conference, val kind: ReminderKind, val daysLeft: Long)

data class ConfState(val announcedOpen: Boolean = false, val lastDailyReminder: LocalDate? = null)

data class BotState(val chats: Set<Long> = emptySet(), val confs: Map<String, ConfState> = emptyMap())

data class EngineResult(val reminders: List<Reminder>, val newState: BotState)

fun confKey(c: Conference): String = "${c.name}|${c.cfpEndDate}"

fun computeReminders(
    conferences: List<Conference>,
    state: BotState,
    today: LocalDate,
): EngineResult {
    val reminders = mutableListOf<Reminder>()
    val newConfs = mutableMapOf<String, ConfState>()

    for (c in conferences) {
        val close = c.cfpClose() ?: continue
        if (close.isBefore(today)) continue // closed -> drop from state (prune)

        val key = confKey(c)
        var cs = state.confs[key] ?: ConfState()
        val daysLeft = ChronoUnit.DAYS.between(today, close)

        if (!cs.announcedOpen) {
            reminders += Reminder(c, ReminderKind.OPENED, daysLeft)
            cs = cs.copy(announcedOpen = true)
        }
        if (daysLeft in 0..REMINDER_WINDOW_DAYS && cs.lastDailyReminder != today) {
            reminders += Reminder(c, ReminderKind.CLOSING_SOON, daysLeft)
            cs = cs.copy(lastDailyReminder = today)
        }
        newConfs[key] = cs
    }

    // newConfs holds only currently-open confs -> closed/removed ones are pruned automatically.
    return EngineResult(reminders, state.copy(confs = newConfs))
}

private fun Conference.cfpUrl(): String = cfpLink.ifBlank { link }

fun Reminder.render(): String = when (kind) {
    ReminderKind.OPENED -> buildString {
        append("📢 CFP OPEN: ${conference.name}\n")
        if (conference.locationName.isNotBlank()) append("📍 ${conference.locationName}\n")
        if (conference.date.isNotBlank()) append("🗓 ${conference.date}\n")
        append("⏳ CFP closes ${conference.cfpEndDate}\n")
        if (conference.cfpUrl().isNotBlank()) append("➡️ ${conference.cfpUrl()}")
    }.trimEnd()
    ReminderKind.CLOSING_SOON -> buildString {
        val phrase = when (daysLeft) {
            0L -> "closes TODAY"
            1L -> "closes TOMORROW"
            else -> "closes in $daysLeft days"
        }
        append("⏰ CFP $phrase: ${conference.name}\n")
        append("⏳ Deadline ${conference.cfpEndDate}\n")
        if (conference.cfpUrl().isNotBlank()) append("➡️ ${conference.cfpUrl()}")
    }.trimEnd()
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "cfpbot.ReminderEngineTest"`
Expected: PASS (all specs).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/cfpbot/ReminderEngine.kt src/test/kotlin/cfpbot/ReminderEngineTest.kt
git commit -m "feat: pure reminder engine with open + closing-soon logic"
```

---

### Task 4: Conference feed source (Ktor)

**Files:**
- Create: `src/main/kotlin/cfpbot/ConferenceSource.kt`
- Test: `src/test/kotlin/cfpbot/ConferenceSourceTest.kt`

**Interfaces:**
- Consumes: `Conference` from Task 2.
- Produces:
  - `class ConferenceSource(private val client: HttpClient, private val url: String = "https://javaconferences.org/conferences.json")`
  - `suspend fun ConferenceSource.fetch(): List<Conference>` — GETs the URL, deserializes the JSON array, ignoring unknown keys.

- [ ] **Step 1: Write the failing test**

The test injects a Ktor `MockEngine` so no network is hit.

Create `src/test/kotlin/cfpbot/ConferenceSourceTest.kt`:

```kotlin
package cfpbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.HttpHeaders
import io.ktor.http.ContentType

class ConferenceSourceTest : StringSpec({
    "fetch deserializes the conferences array, ignoring unknown keys" {
        val body = """
            [
              {"name":"FOSDEM","link":"https://fosdem.org/2026/","locationName":"Brussels, Belgium",
               "coordinates":{"lat":50.8,"lon":4.3,"countryName":"Belgium"},
               "hybrid":false,"date":"31 January-1 February 2026","cfpLink":"","cfpEndDate":""},
              {"name":"Jfokus","link":"https://www.jfokus.se/","locationName":"Stockholm, Sweden",
               "coordinates":{"lat":59.3,"lon":18.0,"countryName":"Sweden"},
               "hybrid":false,"date":"2-4 February 2026",
               "cfpLink":"https://sessionize.com/jfokus-2026","cfpEndDate":"30 September 2025"}
            ]
        """.trimIndent()
        val engine = MockEngine {
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val source = ConferenceSource(HttpClient(engine), url = "https://example.test/conferences.json")
        val confs = source.fetch()
        confs.size shouldBe 2
        confs[1].name shouldBe "Jfokus"
        confs[1].cfpEndDate shouldBe "30 September 2025"
    }
})
```

Add the Ktor mock engine to test dependencies. In `build.gradle.kts` add under `dependencies`:

```kotlin
    testImplementation("io.ktor:ktor-client-mock:3.4.0")
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "cfpbot.ConferenceSourceTest"`
Expected: FAIL — `ConferenceSource` unresolved.

- [ ] **Step 3: Write the implementation**

Create `src/main/kotlin/cfpbot/ConferenceSource.kt`:

```kotlin
package cfpbot

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class ConferenceSource(
    private val client: HttpClient,
    private val url: String = "https://javaconferences.org/conferences.json",
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetch(): List<Conference> {
        val body = client.get(url).bodyAsText()
        return json.decodeFromString(ListSerializer(Conference.serializer()), body)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "cfpbot.ConferenceSourceTest"`
Expected: PASS.

- [ ] **Step 5: Smoke-check the real feed shape (manual, optional)**

This guards against the feed being an object-wrapped array instead of a top-level array. Run a throwaway main if unsure:

Run: `./gradlew run` after temporarily printing `ConferenceSource(HttpClient(CIO)).fetch().size` — expect a non-zero count. If it throws a serialization error about expecting an object, the feed is wrapped; wrap the deserialization in the wrapper type and re-run. Revert the throwaway code before committing.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/cfpbot/ConferenceSource.kt src/test/kotlin/cfpbot/ConferenceSourceTest.kt build.gradle.kts
git commit -m "feat: ktor conference feed source"
```

---

### Task 5: Persistence — DataSource, DDL, StateRepository

**Files:**
- Create: `src/main/kotlin/cfpbot/Db.kt`
- Test: `src/test/kotlin/cfpbot/StateRepositoryTest.kt`

**Interfaces:**
- Consumes: `BotState`, `ConfState` from Task 3.
- Produces:
  - `fun createDataSource(dbPath: String): HikariDataSource`
  - `fun runDdl(ds: DataSource)` — creates `registered_chat`, `reminder_state`, and db-scheduler's `scheduled_tasks` tables (`IF NOT EXISTS`).
  - `class StateRepository(private val ds: DataSource)` with:
    - `fun loadState(): BotState` — reads both app tables.
    - `fun saveReminderState(confs: Map<String, ConfState>)` — replaces `reminder_state` contents transactionally (does **not** touch chats).
    - `fun addChat(chatId: Long)` — idempotent insert into `registered_chat`.

- [ ] **Step 1: Write the failing test**

Uses an in-memory H2 datasource for isolation.

Create `src/test/kotlin/cfpbot/StateRepositoryTest.kt`:

```kotlin
package cfpbot

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDate

private fun memDataSource(name: String): HikariDataSource =
    HikariDataSource(HikariConfig().apply {
        jdbcUrl = "jdbc:h2:mem:$name;DB_CLOSE_DELAY=-1"
        username = "sa"
        password = ""
        maximumPoolSize = 2
    })

class StateRepositoryTest : StringSpec({
    "round-trips chats and reminder state" {
        val ds = memDataSource("roundtrip")
        runDdl(ds)
        val repo = StateRepository(ds)

        repo.addChat(42L)
        repo.addChat(99L)
        repo.addChat(42L) // idempotent
        repo.saveReminderState(
            mapOf(
                "Devoxx|1 August 2026" to ConfState(announcedOpen = true, lastDailyReminder = null),
                "KotlinConf|5 June 2026" to ConfState(announcedOpen = true, lastDailyReminder = LocalDate.of(2026, 6, 1)),
            ),
        )

        val state = repo.loadState()
        state.chats shouldBe setOf(42L, 99L)
        state.confs["Devoxx|1 August 2026"] shouldBe ConfState(announcedOpen = true, lastDailyReminder = null)
        state.confs["KotlinConf|5 June 2026"] shouldBe
            ConfState(announcedOpen = true, lastDailyReminder = LocalDate.of(2026, 6, 1))
    }

    "saveReminderState replaces prior rows and leaves chats intact" {
        val ds = memDataSource("replace")
        runDdl(ds)
        val repo = StateRepository(ds)

        repo.addChat(7L)
        repo.saveReminderState(mapOf("Old|1 May 2026" to ConfState(announcedOpen = true)))
        repo.saveReminderState(mapOf("New|1 July 2026" to ConfState(announcedOpen = true)))

        val state = repo.loadState()
        state.confs.keys shouldBe setOf("New|1 July 2026")
        state.chats shouldBe setOf(7L)
    }
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "cfpbot.StateRepositoryTest"`
Expected: FAIL — `runDdl` / `StateRepository` unresolved.

- [ ] **Step 3: Write the implementation**

Create `src/main/kotlin/cfpbot/Db.kt`:

```kotlin
package cfpbot

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.time.LocalDate
import javax.sql.DataSource

fun createDataSource(dbPath: String): HikariDataSource =
    HikariDataSource(HikariConfig().apply {
        jdbcUrl = "jdbc:h2:file:$dbPath;AUTO_SERVER=TRUE"
        username = "sa"
        password = ""
        maximumPoolSize = 4
    })

private val SCHEMA = listOf(
    """
    CREATE TABLE IF NOT EXISTS registered_chat (
        chat_id BIGINT PRIMARY KEY
    )
    """.trimIndent(),
    """
    CREATE TABLE IF NOT EXISTS reminder_state (
        conf_key VARCHAR(512) PRIMARY KEY,
        announced_open BOOLEAN NOT NULL,
        last_daily_reminder DATE
    )
    """.trimIndent(),
    // db-scheduler's table (H2-compatible).
    """
    CREATE TABLE IF NOT EXISTS scheduled_tasks (
        task_name VARCHAR(255) NOT NULL,
        task_instance VARCHAR(255) NOT NULL,
        task_data BLOB,
        execution_time TIMESTAMP WITH TIME ZONE NOT NULL,
        picked BOOLEAN NOT NULL,
        picked_by VARCHAR(50),
        last_success TIMESTAMP WITH TIME ZONE,
        last_failure TIMESTAMP WITH TIME ZONE,
        consecutive_failures INT,
        last_heartbeat TIMESTAMP WITH TIME ZONE,
        version BIGINT NOT NULL,
        PRIMARY KEY (task_name, task_instance)
    )
    """.trimIndent(),
)

fun runDdl(ds: DataSource) {
    ds.connection.use { conn ->
        conn.createStatement().use { st ->
            SCHEMA.forEach { st.execute(it) }
        }
    }
}

class StateRepository(private val ds: DataSource) {

    fun addChat(chatId: Long) {
        ds.connection.use { conn ->
            conn.prepareStatement("MERGE INTO registered_chat (chat_id) VALUES (?)").use { ps ->
                ps.setLong(1, chatId)
                ps.executeUpdate()
            }
        }
    }

    fun loadState(): BotState {
        val chats = mutableSetOf<Long>()
        val confs = mutableMapOf<String, ConfState>()
        ds.connection.use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT chat_id FROM registered_chat").use { rs ->
                    while (rs.next()) chats += rs.getLong("chat_id")
                }
                st.executeQuery(
                    "SELECT conf_key, announced_open, last_daily_reminder FROM reminder_state",
                ).use { rs ->
                    while (rs.next()) {
                        val last = rs.getDate("last_daily_reminder")?.toLocalDate()
                        confs[rs.getString("conf_key")] =
                            ConfState(rs.getBoolean("announced_open"), last)
                    }
                }
            }
        }
        return BotState(chats, confs)
    }

    fun saveReminderState(confs: Map<String, ConfState>) {
        ds.connection.use { conn ->
            conn.autoCommit = false
            try {
                conn.createStatement().use { it.execute("DELETE FROM reminder_state") }
                conn.prepareStatement(
                    "INSERT INTO reminder_state (conf_key, announced_open, last_daily_reminder) VALUES (?, ?, ?)",
                ).use { ps ->
                    confs.forEach { (key, cs) ->
                        ps.setString(1, key)
                        ps.setBoolean(2, cs.announcedOpen)
                        ps.setObject(3, cs.lastDailyReminder?.let(java.sql.Date::valueOf))
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "cfpbot.StateRepositoryTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/cfpbot/Db.kt src/test/kotlin/cfpbot/StateRepositoryTest.kt
git commit -m "feat: h2 persistence for chats and reminder state"
```

---

### Task 6: Notifier and CheckTask orchestration

**Files:**
- Create: `src/main/kotlin/cfpbot/Notifier.kt`
- Create: `src/main/kotlin/cfpbot/CheckTask.kt`
- Test: `src/test/kotlin/cfpbot/CheckTaskTest.kt`

**Interfaces:**
- Consumes: `ConferenceSource.fetch()` (Task 4), `computeReminders` + `render()` (Task 3), `StateRepository` (Task 5).
- Produces:
  - `fun interface Notifier { suspend fun send(chatId: Long, text: String) }`
  - `class CheckTask(source, repo, notifier, clock = { LocalDate.now() })` with `suspend fun run()`: fetch feed → load state → compute → send each reminder to every registered chat → persist new reminder state.

- [ ] **Step 1: Write the failing test**

Uses a capturing `Notifier`, in-memory H2 repo, a `MockEngine` feed, and a fixed clock. Verifies a reminder reaches every registered chat and that running twice the same day does not re-send.

Create `src/test/kotlin/cfpbot/CheckTaskTest.kt`:

```kotlin
package cfpbot

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldHaveSize
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import java.time.LocalDate

class CheckTaskTest : StringSpec({
    fun memDs(name: String) = HikariDataSource(HikariConfig().apply {
        jdbcUrl = "jdbc:h2:mem:$name;DB_CLOSE_DELAY=-1"
        username = "sa"; password = ""; maximumPoolSize = 2
    })

    val feed = """
        [{"name":"KotlinConf","link":"https://kotlinconf.com","locationName":"Copenhagen",
          "hybrid":false,"date":"May 2026","cfpLink":"https://cfp/kotlin","cfpEndDate":"5 June 2026"}]
    """.trimIndent()

    fun sourceReturning(body: String): ConferenceSource {
        val engine = MockEngine { respond(content = body, status = HttpStatusCode.OK) }
        return ConferenceSource(HttpClient(engine), url = "https://example.test/feed.json")
    }

    "sends a reminder to every registered chat and persists state" {
        val ds = memDs("checktask1")
        runDdl(ds)
        val repo = StateRepository(ds)
        repo.addChat(1L)
        repo.addChat(2L)

        val sent = mutableListOf<Pair<Long, String>>()
        val notifier = Notifier { chatId, text -> sent += chatId to text }
        val task = CheckTask(sourceReturning(feed), repo, notifier, clock = { LocalDate.of(2026, 6, 1) })

        runBlocking { task.run() }

        // One OPENED + one CLOSING_SOON (4 days out), each to 2 chats = 4 sends.
        sent shouldHaveSize 4
        sent.map { it.first }.toSet() shouldBe setOf(1L, 2L)
        repo.loadState().confs["KotlinConf|5 June 2026"]!!.announcedOpen shouldBe true
    }

    "running again the same day sends nothing new" {
        val ds = memDs("checktask2")
        runDdl(ds)
        val repo = StateRepository(ds)
        repo.addChat(1L)

        val sent = mutableListOf<Pair<Long, String>>()
        val notifier = Notifier { chatId, text -> sent += chatId to text }
        val task = CheckTask(sourceReturning(feed), repo, notifier, clock = { LocalDate.of(2026, 6, 1) })

        runBlocking { task.run() }
        val afterFirst = sent.size
        runBlocking { task.run() }
        sent.size shouldBe afterFirst
    }
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "cfpbot.CheckTaskTest"`
Expected: FAIL — `Notifier` / `CheckTask` unresolved.

- [ ] **Step 3: Write the Notifier**

Create `src/main/kotlin/cfpbot/Notifier.kt`:

```kotlin
package cfpbot

import eu.vendeli.tgbot.TelegramBot
import eu.vendeli.tgbot.api.message

fun interface Notifier {
    suspend fun send(chatId: Long, text: String)
}

class TelegramNotifier(private val bot: TelegramBot) : Notifier {
    override suspend fun send(chatId: Long, text: String) {
        message { text }.send(chatId, bot)
    }
}
```

> If `eu.vendeli.tgbot.api.message` does not resolve, check the generated import in the README example (`message { }` / `sendMessage { }`); the package is `eu.vendeli.tgbot.api`. The `send(to: Long, via: TelegramBot)` overload is confirmed for 9.5.0.

- [ ] **Step 4: Write CheckTask**

Create `src/main/kotlin/cfpbot/CheckTask.kt`:

```kotlin
package cfpbot

import java.time.LocalDate

class CheckTask(
    private val source: ConferenceSource,
    private val repo: StateRepository,
    private val notifier: Notifier,
    private val clock: () -> LocalDate = { LocalDate.now() },
) {
    suspend fun run() {
        val today = clock()
        val conferences = source.fetch()
        val state = repo.loadState()
        val (reminders, newState) = computeReminders(conferences, state, today)

        for (reminder in reminders) {
            val text = reminder.render()
            for (chatId in state.chats) {
                notifier.send(chatId, text)
            }
        }
        repo.saveReminderState(newState.confs)
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests "cfpbot.CheckTaskTest"`
Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/cfpbot/Notifier.kt src/main/kotlin/cfpbot/CheckTask.kt src/test/kotlin/cfpbot/CheckTaskTest.kt
git commit -m "feat: notifier abstraction and check-task orchestration"
```

---

### Task 7: /start command registration

**Files:**
- Create: `src/main/kotlin/cfpbot/Commands.kt`
- Test: `src/test/kotlin/cfpbot/RegistryTest.kt`

**Interfaces:**
- Consumes: `StateRepository.addChat` (Task 5).
- Produces:
  - `object Registry { lateinit var repo: StateRepository }` — set once in `main`, read by the handler.
  - `@CommandHandler(["/start"]) suspend fun start(update: ProcessedUpdate, bot: TelegramBot)` — registers `update.getChat().id` and confirms.

The handler itself runs only inside the framework, so the testable seam is `Registry.repo.addChat`. We test that the repo registers a chat id (the handler is a thin one-liner delegating to it).

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/cfpbot/RegistryTest.kt`:

```kotlin
package cfpbot

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class RegistryTest : StringSpec({
    "Registry exposes the repo used to register a chat" {
        val ds = HikariDataSource(HikariConfig().apply {
            jdbcUrl = "jdbc:h2:mem:registry;DB_CLOSE_DELAY=-1"
            username = "sa"; password = ""; maximumPoolSize = 2
        })
        runDdl(ds)
        Registry.repo = StateRepository(ds)

        Registry.repo.addChat(555L)

        Registry.repo.loadState().chats shouldBe setOf(555L)
    }
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "cfpbot.RegistryTest"`
Expected: FAIL — `Registry` unresolved.

- [ ] **Step 3: Write the implementation**

Create `src/main/kotlin/cfpbot/Commands.kt`:

```kotlin
package cfpbot

import eu.vendeli.tgbot.TelegramBot
import eu.vendeli.tgbot.annotations.CommandHandler
import eu.vendeli.tgbot.api.message
import eu.vendeli.tgbot.types.component.ProcessedUpdate
import eu.vendeli.tgbot.types.component.getChat

// ponytail: process-global singleton so the framework-invoked top-level handler can reach the repo.
// Single-process bot, set once in main(); promote to the framework's ClassManager DI only if handlers multiply.
object Registry {
    lateinit var repo: StateRepository
}

@CommandHandler(["/start"])
suspend fun start(update: ProcessedUpdate, bot: TelegramBot) {
    val chat = update.getChat()
    Registry.repo.addChat(chat.id)
    message {
        "✅ Registered this chat for Java conference CFP notifications.\n" +
            "You'll get a heads-up when a CFP opens and daily reminders in its final week."
    }.send(chat.id, bot)
}
```

> `getChat()` and `ProcessedUpdate` live in `eu.vendeli.tgbot.types.component`. `getChat()` returns the `Chat` (works for private chats and channel posts). If the import path differs in 9.5.0, resolve via IDE auto-import — the function name is `getChat`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "cfpbot.RegistryTest"`
Expected: PASS.

- [ ] **Step 5: Verify the whole suite still compiles and passes**

Run: `./gradlew test`
Expected: PASS — all specs, KSP generates the `/start` handler registration without error.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/cfpbot/Commands.kt src/test/kotlin/cfpbot/RegistryTest.kt
git commit -m "feat: /start command registers chat for notifications"
```

---

### Task 8: db-scheduler wiring and Main entrypoint

**Files:**
- Create: `src/main/kotlin/cfpbot/Scheduler.kt`
- Modify: `src/main/kotlin/cfpbot/Main.kt` (replace the Task 1 placeholder)

**Interfaces:**
- Consumes: `CheckTask` (Task 6), `createDataSource`/`runDdl`/`StateRepository` (Task 5), `Registry` (Task 7), `ConferenceSource` (Task 4), `TelegramNotifier` (Task 6).
- Produces:
  - `fun startScheduler(ds: DataSource, check: CheckTask, runAt: LocalTime): Scheduler` — registers a db-scheduler recurring task `cfp-check` running daily at `runAt`, starts it, returns the scheduler.
  - `suspend fun main()` — wires DataSource, DDL, repo, bot, source, notifier, check; starts the scheduler; calls `bot.handleUpdates()`.

- [ ] **Step 1: Write the scheduler wiring**

Create `src/main/kotlin/cfpbot/Scheduler.kt`:

```kotlin
package cfpbot

import com.github.kagkarlsson.scheduler.Scheduler
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask
import com.github.kagkarlsson.scheduler.task.helper.Tasks
import com.github.kagkarlsson.scheduler.task.schedule.Schedules
import kotlinx.coroutines.runBlocking
import java.time.LocalTime
import javax.sql.DataSource

fun cfpCheckTask(check: CheckTask, runAt: LocalTime): RecurringTask<Void> =
    Tasks.recurring("cfp-check", Schedules.daily(runAt))
        .execute { _, _ ->
            // db-scheduler runs this on its own thread pool; runBlocking bridges to the suspend world.
            runBlocking { check.run() }
        }

fun startScheduler(ds: DataSource, check: CheckTask, runAt: LocalTime): Scheduler {
    val task = cfpCheckTask(check, runAt)
    val scheduler = Scheduler.create(ds, task)
        .threads(1)
        .build()
    scheduler.start()
    return scheduler
}
```

> db-scheduler API note: `Scheduler.create(dataSource, knownTasks...)` registers recurring tasks; `.start()` schedules them and runs any missed executions (persisted in `scheduled_tasks`). If the 15.x builder signature differs, the equivalent is `Scheduler.create(ds).startTasks(task).threads(1).build()` — check the resolved `Scheduler` class.

- [ ] **Step 2: Write the entrypoint**

Replace `src/main/kotlin/cfpbot/Main.kt`:

```kotlin
package cfpbot

import eu.vendeli.tgbot.TelegramBot
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import java.time.LocalTime

suspend fun main() {
    val token = System.getenv("BOT_TOKEN")
        ?: error("BOT_TOKEN environment variable is required")
    val dbPath = System.getenv("DB_PATH") ?: "./data/cfpbot"
    val runAt = (System.getenv("CHECK_HOUR")?.toIntOrNull() ?: 9).let { LocalTime.of(it, 0) }

    val ds = createDataSource(dbPath)
    runDdl(ds)
    val repo = StateRepository(ds)
    Registry.repo = repo

    val client = HttpClient(CIO)
    val source = ConferenceSource(client)
    val bot = TelegramBot(token)
    val notifier = TelegramNotifier(bot)
    val check = CheckTask(source, repo, notifier)

    startScheduler(ds, check, runAt)
    println("cfpbot: scheduler started (daily at $runAt), listening for /start…")

    bot.handleUpdates()
}
```

- [ ] **Step 3: Verify it compiles and the suite still passes**

Run: `./gradlew test`
Expected: PASS — compilation of `Scheduler.kt` and `Main.kt` succeeds; all prior specs green.

- [ ] **Step 4: Verify the app boots (manual smoke)**

Get a bot token from @BotFather first. Then:

Run: `BOT_TOKEN=<your-token> DB_PATH=./data/cfpbot ./gradlew run`
Expected: prints `cfpbot: scheduler started … listening for /start…` and stays running (long polling). In Telegram, DM the bot `/start` → it replies with the registration confirmation. `Ctrl-C` to stop. Re-run and confirm it still starts (state persisted in `./data/cfpbot.mv.db`).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/cfpbot/Scheduler.kt src/main/kotlin/cfpbot/Main.kt
git commit -m "feat: db-scheduler daily check and bot entrypoint"
```

---

### Task 9: Operator docs and on-demand check command

**Files:**
- Create: `README.md`
- Modify: `src/main/kotlin/cfpbot/Commands.kt` (add `/check` for a manual run, optional but useful for verification)

**Interfaces:**
- Consumes: `Registry.repo`, `CheckTask` (Task 6).
- Produces:
  - `object Registry { lateinit var repo: StateRepository; lateinit var check: CheckTask }` — extended to hold the check task.
  - `@CommandHandler(["/check"]) suspend fun check(...)` — triggers `Registry.check.run()` immediately (so you don't have to wait until the scheduled hour to verify end-to-end).
  - `README.md` documenting setup, env vars, channel registration, and run.

- [ ] **Step 1: Extend Registry and add the /check handler**

Edit `src/main/kotlin/cfpbot/Commands.kt` — change the `Registry` object and append a handler:

```kotlin
object Registry {
    lateinit var repo: StateRepository
    lateinit var check: CheckTask
}
```

Append:

```kotlin
@CommandHandler(["/check"])
suspend fun check(update: ProcessedUpdate, bot: TelegramBot) {
    val chat = update.getChat()
    Registry.check.run()
    message { "🔄 Ran the CFP check now." }.send(chat.id, bot)
}
```

- [ ] **Step 2: Wire Registry.check in Main**

Edit `src/main/kotlin/cfpbot/Main.kt` — after `val check = CheckTask(...)`, add:

```kotlin
    Registry.check = check
```

- [ ] **Step 3: Verify it still compiles and passes**

Run: `./gradlew test`
Expected: PASS — `RegistryTest` still green (it only touches `Registry.repo`).

- [ ] **Step 4: Write the README**

Create `README.md`:

```markdown
# Conference CFP Notifier Bot

Telegram bot that watches [javaconferences.org](https://javaconferences.org/conferences.json)
and notifies you (DM or channel) about conference Call-for-Papers deadlines:

- **When a CFP first appears open** — a one-time heads-up.
- **Daily during the final week** before the CFP closes (from 7 days out through the close day).

## Setup

1. Create a bot with [@BotFather](https://t.me/BotFather) and copy the token.
2. Run the bot:

   ```bash
   BOT_TOKEN=<token> ./gradlew run
   ```

3. **Register a private chat:** DM your bot `/start`.
4. **Register a channel:** add the bot to the channel as an admin, then post `/start`
   in the channel. The bot stores the channel's chat id and posts reminders there.
5. `/check` triggers an immediate run (handy for verifying the wiring).

## Configuration (env vars)

| Variable     | Default          | Meaning                                   |
|--------------|------------------|-------------------------------------------|
| `BOT_TOKEN`  | (required)       | Telegram bot token from @BotFather        |
| `DB_PATH`    | `./data/cfpbot`  | H2 file path (state + scheduler survive restarts) |
| `CHECK_HOUR` | `9`              | Hour of day (0–23, server local time) for the daily check |

## How it works

State (registered chats + which reminders have been sent) lives in an embedded H2
database. The daily check is a pure recompute from the live feed plus that state, so
restarts never reset progress and a missed day self-heals on the next run. The schedule
itself is persisted by [db-scheduler](https://github.com/kagkarlsson/db-scheduler),
which also runs any missed execution on startup.

Run the tests with `./gradlew test`.
```

- [ ] **Step 5: Manual end-to-end verification**

Run: `BOT_TOKEN=<token> ./gradlew run`, DM `/start`, then `/check`.
Expected: if any conference in the live feed has an open CFP you haven't been notified about, you receive an OPENED message; if any closes within 7 days, a CLOSING_SOON message. A second `/check` the same day sends nothing new.

- [ ] **Step 6: Commit**

```bash
git add README.md src/main/kotlin/cfpbot/Commands.kt src/main/kotlin/cfpbot/Main.kt
git commit -m "feat: /check command and operator docs"
```

---

## Self-Review Notes

- **Spec coverage:**
  - "notify when CFP opens" → first-seen-open in `computeReminders` (Task 3), emits `OPENED` once per `name|cfpEndDate`.
  - "the week before CFP closes, and every day after that until it closes" → `daysLeft in 0..7` daily `CLOSING_SOON`, deduped per day via `lastDailyReminder` (Task 3).
  - "private chat → DM; channel → post to channel" → `/start` stores `getChat().id` for both; reminders sent to every registered chat (Tasks 6, 7).
  - "use vendelieu/telegram-bot framework" → Tasks 1, 6, 7, 8.
  - "source = javaconferences.org" → `ConferenceSource` against `conferences.json` (Task 4).
  - "persistent across restart" → H2 + db-scheduler (Tasks 5, 8).
- **Data reality:** the feed has only `cfpEndDate` (no open date), so "opens" is modeled as first-seen-open — confirmed with the user.
- **Type consistency:** `confKey`, `ConfState(announcedOpen, lastDailyReminder)`, `BotState(chats, confs)`, `Reminder(conference, kind, daysLeft)`, `Notifier.send(chatId, text)`, `CheckTask.run()`, `StateRepository.{loadState, saveReminderState, addChat}` are used identically across tasks.
- **Known external-API risks flagged inline** (call out, verify on first compile): exact `message{}` import path, `getChat()` import path, and the db-scheduler 15.x builder signature. Each has a fallback noted.
```