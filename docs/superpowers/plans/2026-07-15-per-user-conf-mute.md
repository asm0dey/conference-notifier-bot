# Per-user Conference Mute Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a user stop (and resume) reminders for one specific conference, via an inline `🔕 Stop reminders` button on reminders and via reply `/stop` / `/resume`, without affecting other users or other conferences.

**Architecture:** A conference is identified everywhere by a short `token` = first 12 hex of `SHA-256(confKey)` (fits Telegram's 64-char callback-data limit). Three H2 tables: `conf_directory` (token→name), `muted_conf` (chat×token), `sent_reminder` (chat×message_id→token, for resolving reply `/stop`). `CheckTask` skips muted `(chat, token)` pairs and prunes all three tables when a conference closes. New vendeli handlers wire the buttons and commands.

**Tech Stack:** Kotlin 2.4, vendeli telegram-bot 9.5.0, H2 (JDBC + HikariCP), kotlinx-coroutines, Kotest (StringSpec).

## Global Constraints

- Kotlin `val` by default; `data class` for value types; Kotest matchers (`shouldBe`), never JUnit asserts.
- Never read/print/commit `.env` or `BOT_TOKEN`.
- vendeli default command parsing: `commandDelimiter='?'`, `parametersDelimiter='&'`, `parameterValueDelimiter='='`. So callback data `stop?token=<t>` parses to command `stop` + param `token=<t>`. Do NOT change these delimiters (would alter `/`-command parsing).
- `confKey(c) = "${c.name}|${c.cfpEndDate}"` (existing, in `ReminderEngine.kt`).
- Muting suppresses BOTH `OPENED` and `CLOSING_SOON` auto-reminders for a conf. `/active` is unaffected.
- Telegram `message_id` is a `Long` (`Message.getMessageId(): long`).

---

### Task 1: `confToken` helper

**Files:**
- Modify: `src/main/kotlin/cfpbot/ReminderEngine.kt` (add top-level fun near `confKey`, line 25)
- Test: `src/test/kotlin/cfpbot/ReminderEngineTest.kt` (add cases)

**Interfaces:**
- Produces: `fun confToken(confKey: String): String` — deterministic 12-hex-char token.

- [ ] **Step 1: Write the failing test**

Add to `src/test/kotlin/cfpbot/ReminderEngineTest.kt` inside the existing `StringSpec({ ... })` body:

```kotlin
"confToken is deterministic and 12 hex chars" {
    val k = confKey(Conference(name = "KotlinConf", cfpEndDate = "5 June 2026"))
    confToken(k) shouldBe confToken(k)
    confToken(k).length shouldBe 12
    confToken(k).all { it in "0123456789abcdef" } shouldBe true
}

"confToken differs for different keys" {
    confToken("A|2026") shouldNotBe confToken("B|2026")
}
```

Ensure these imports exist at the top of the file:

```kotlin
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
```

(Check `Conference`'s constructor: use named args `name` and `cfpEndDate`; other fields have defaults. If they do not, construct it the same way other tests in this repo build a `Conference`.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'cfpbot.ReminderEngineTest'`
Expected: FAIL — unresolved reference `confToken`.

- [ ] **Step 3: Write minimal implementation**

Add to `src/main/kotlin/cfpbot/ReminderEngine.kt` just below `fun confKey(...)`:

```kotlin
fun confToken(confKey: String): String =
    java.security.MessageDigest.getInstance("SHA-256")
        .digest(confKey.toByteArray())
        .joinToString("") { "%02x".format(it) }
        .take(12)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests 'cfpbot.ReminderEngineTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/cfpbot/ReminderEngine.kt src/test/kotlin/cfpbot/ReminderEngineTest.kt
git commit -m "feat: confToken — short stable per-conference token"
```

---

### Task 2: Mute tables + repository methods

**Files:**
- Modify: `src/main/kotlin/cfpbot/Db.kt` (add 3 tables to `SCHEMA`; add methods to `StateRepository`)
- Test: `src/test/kotlin/cfpbot/StateRepositoryTest.kt` (add cases)

**Interfaces:**
- Consumes: `confToken` (Task 1) — only in callers, not here.
- Produces (all on `StateRepository`):
  - `fun mute(chatId: Long, token: String)`
  - `fun unmute(chatId: Long, token: String)`
  - `fun loadMuted(): Map<Long, Set<String>>`
  - `fun upsertConfDirectory(token: String, confKey: String, name: String)`
  - `fun recordSentReminder(chatId: Long, messageId: Long, token: String)`
  - `fun tokenForMessage(chatId: Long, messageId: Long): String?`
  - `fun mutedConfsFor(chatId: Long): List<Pair<String, String>>` — list of `(token, name)`
  - `fun pruneTokens(liveTokens: Set<String>)` — delete rows in all 3 tables whose token ∉ liveTokens

- [ ] **Step 1: Write the failing test**

Add to `src/test/kotlin/cfpbot/StateRepositoryTest.kt` inside the `StringSpec` body. Reuse the file's existing in-memory datasource helper (this repo's `StateRepositoryTest` already builds an H2 `mem:` datasource + `runDdl`; mirror its pattern — shown here as `memDs`/`runDdl`):

```kotlin
"mute / loadMuted / unmute round-trip" {
    val ds = memDs("mute1"); runDdl(ds)
    val repo = StateRepository(ds)
    repo.mute(1L, "aaa")
    repo.mute(1L, "bbb")
    repo.mute(2L, "aaa")
    repo.loadMuted() shouldBe mapOf(1L to setOf("aaa", "bbb"), 2L to setOf("aaa"))
    repo.mute(1L, "aaa") // idempotent
    repo.loadMuted()[1L] shouldBe setOf("aaa", "bbb")
    repo.unmute(1L, "aaa")
    repo.loadMuted()[1L] shouldBe setOf("bbb")
}

"sent_reminder resolves a message back to its token" {
    val ds = memDs("mute2"); runDdl(ds)
    val repo = StateRepository(ds)
    repo.recordSentReminder(7L, 100L, "tok1")
    repo.tokenForMessage(7L, 100L) shouldBe "tok1"
    repo.tokenForMessage(7L, 999L) shouldBe null
    repo.recordSentReminder(7L, 100L, "tok2") // MERGE overwrites same (chat,msg)
    repo.tokenForMessage(7L, 100L) shouldBe "tok2"
}

"mutedConfsFor joins directory for names" {
    val ds = memDs("mute3"); runDdl(ds)
    val repo = StateRepository(ds)
    repo.upsertConfDirectory("tok1", "KotlinConf|5 June 2026", "KotlinConf")
    repo.upsertConfDirectory("tok2", "Devoxx|1 July 2026", "Devoxx")
    repo.mute(5L, "tok1"); repo.mute(5L, "tok2")
    repo.mutedConfsFor(5L).toSet() shouldBe setOf("tok1" to "KotlinConf", "tok2" to "Devoxx")
    repo.mutedConfsFor(6L) shouldBe emptyList()
}

"pruneTokens deletes rows for closed confs across all tables" {
    val ds = memDs("mute4"); runDdl(ds)
    val repo = StateRepository(ds)
    repo.upsertConfDirectory("live", "L|2026", "Live")
    repo.upsertConfDirectory("dead", "D|2026", "Dead")
    repo.mute(1L, "live"); repo.mute(1L, "dead")
    repo.recordSentReminder(1L, 10L, "live"); repo.recordSentReminder(1L, 11L, "dead")

    repo.pruneTokens(setOf("live"))

    repo.loadMuted()[1L] shouldBe setOf("live")
    repo.tokenForMessage(1L, 11L) shouldBe null      // dead sent_reminder gone
    repo.mutedConfsFor(1L) shouldBe listOf("live" to "Live") // dead directory gone

    repo.pruneTokens(emptySet())                     // empty = prune everything
    repo.loadMuted() shouldBe emptyMap()
}
```

Ensure `import io.kotest.matchers.shouldBe` is present (it is, in the existing file).

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'cfpbot.StateRepositoryTest'`
Expected: FAIL — unresolved references `mute`, `loadMuted`, etc.

- [ ] **Step 3a: Add the schema**

In `src/main/kotlin/cfpbot/Db.kt`, append these three entries to the `SCHEMA` list (after the `send_queue` block, before the db-scheduler block is fine — order within the list does not matter):

```kotlin
    """
    CREATE TABLE IF NOT EXISTS conf_directory (
        token    VARCHAR(16) PRIMARY KEY,
        conf_key VARCHAR(512) NOT NULL,
        name     VARCHAR(512) NOT NULL
    )
    """.trimIndent(),
    """
    CREATE TABLE IF NOT EXISTS muted_conf (
        chat_id BIGINT      NOT NULL,
        token   VARCHAR(16) NOT NULL,
        PRIMARY KEY (chat_id, token)
    )
    """.trimIndent(),
    """
    CREATE TABLE IF NOT EXISTS sent_reminder (
        chat_id    BIGINT      NOT NULL,
        message_id BIGINT      NOT NULL,
        token      VARCHAR(16) NOT NULL,
        PRIMARY KEY (chat_id, message_id)
    )
    """.trimIndent(),
```

- [ ] **Step 3b: Add repository methods**

In `src/main/kotlin/cfpbot/Db.kt`, add these methods inside `class StateRepository` (after `removeChat`):

```kotlin
    fun mute(chatId: Long, token: String) {
        ds.connection.use { conn ->
            conn.prepareStatement("MERGE INTO muted_conf (chat_id, token) VALUES (?, ?)").use { ps ->
                ps.setLong(1, chatId); ps.setString(2, token); ps.executeUpdate()
            }
        }
    }

    fun unmute(chatId: Long, token: String) {
        ds.connection.use { conn ->
            conn.prepareStatement("DELETE FROM muted_conf WHERE chat_id = ? AND token = ?").use { ps ->
                ps.setLong(1, chatId); ps.setString(2, token); ps.executeUpdate()
            }
        }
    }

    fun loadMuted(): Map<Long, Set<String>> {
        val out = mutableMapOf<Long, MutableSet<String>>()
        ds.connection.use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT chat_id, token FROM muted_conf").use { rs ->
                    while (rs.next()) {
                        out.getOrPut(rs.getLong("chat_id")) { mutableSetOf() } += rs.getString("token")
                    }
                }
            }
        }
        return out
    }

    fun upsertConfDirectory(token: String, confKey: String, name: String) {
        ds.connection.use { conn ->
            conn.prepareStatement(
                "MERGE INTO conf_directory (token, conf_key, name) VALUES (?, ?, ?)",
            ).use { ps ->
                ps.setString(1, token); ps.setString(2, confKey); ps.setString(3, name)
                ps.executeUpdate()
            }
        }
    }

    fun recordSentReminder(chatId: Long, messageId: Long, token: String) {
        ds.connection.use { conn ->
            conn.prepareStatement(
                "MERGE INTO sent_reminder (chat_id, message_id, token) VALUES (?, ?, ?)",
            ).use { ps ->
                ps.setLong(1, chatId); ps.setLong(2, messageId); ps.setString(3, token)
                ps.executeUpdate()
            }
        }
    }

    fun tokenForMessage(chatId: Long, messageId: Long): String? =
        ds.connection.use { conn ->
            conn.prepareStatement(
                "SELECT token FROM sent_reminder WHERE chat_id = ? AND message_id = ?",
            ).use { ps ->
                ps.setLong(1, chatId); ps.setLong(2, messageId)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getString("token") else null }
            }
        }

    fun mutedConfsFor(chatId: Long): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        ds.connection.use { conn ->
            conn.prepareStatement(
                "SELECT m.token, d.name FROM muted_conf m " +
                    "JOIN conf_directory d ON d.token = m.token WHERE m.chat_id = ?",
            ).use { ps ->
                ps.setLong(1, chatId)
                ps.executeQuery().use { rs ->
                    while (rs.next()) out += rs.getString("token") to rs.getString("name")
                }
            }
        }
        return out
    }

    fun pruneTokens(liveTokens: Set<String>) {
        ds.connection.use { conn ->
            conn.autoCommit = false
            try {
                if (liveTokens.isEmpty()) {
                    conn.createStatement().use { st ->
                        st.execute("DELETE FROM conf_directory")
                        st.execute("DELETE FROM muted_conf")
                        st.execute("DELETE FROM sent_reminder")
                    }
                } else {
                    val placeholders = liveTokens.joinToString(",") { "?" }
                    for (table in listOf("conf_directory", "muted_conf", "sent_reminder")) {
                        conn.prepareStatement(
                            "DELETE FROM $table WHERE token NOT IN ($placeholders)",
                        ).use { ps ->
                            liveTokens.forEachIndexed { i, t -> ps.setString(i + 1, t) }
                            ps.executeUpdate()
                        }
                    }
                }
                conn.commit()
            } catch (e: Exception) {
                conn.rollback(); throw e
            } finally {
                conn.autoCommit = true
            }
        }
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests 'cfpbot.StateRepositoryTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/cfpbot/Db.kt src/test/kotlin/cfpbot/StateRepositoryTest.kt
git commit -m "feat: mute/directory/sent_reminder tables + repository methods"
```

---

### Task 3: `Notifier.sendReminder` with Stop button + message id

**Files:**
- Modify: `src/main/kotlin/cfpbot/Notifier.kt`
- Test: `src/test/kotlin/cfpbot/NotifierTest.kt` (add a case; if the file has no datasource needs, just add to its `StringSpec`)

**Interfaces:**
- Produces: `suspend fun Notifier.sendReminder(chatId: Long, text: String, stopToken: String): Long?`
  — default impl delegates to `send` and returns `null`; `TelegramNotifier` overrides to attach the button and return the sent `message_id`.
- Consumes: nothing new.

Rationale: adding a **defaulted** method keeps `send(chatId, text)` as the sole abstract method, so every existing `Notifier { c, t -> ... }` lambda and `object : Notifier` fake in the test suite compiles unchanged.

- [ ] **Step 1: Write the failing test**

Add to `src/test/kotlin/cfpbot/NotifierTest.kt` inside its `StringSpec` body:

```kotlin
"default sendReminder delegates to send and returns null" {
    val sent = mutableListOf<Pair<Long, String>>()
    val notifier = Notifier { chatId, text -> sent += chatId to text }
    val id = kotlinx.coroutines.runBlocking { notifier.sendReminder(9L, "hi", "tok") }
    id shouldBe null
    sent shouldBe listOf(9L to "hi")
}
```

Ensure `import io.kotest.matchers.shouldBe` is present.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'cfpbot.NotifierTest'`
Expected: FAIL — unresolved reference `sendReminder`.

- [ ] **Step 3: Implement**

In `src/main/kotlin/cfpbot/Notifier.kt`, add the default method to the `Notifier` interface (after `sendLocation`):

```kotlin
    // Sends a reminder carrying a "🔕 Stop reminders" inline button and returns the Telegram
    // message_id (so callers can map it back to a conference for reply /stop). Default delegates
    // to send() and returns null — fakes and non-Telegram impls need no button/id.
    suspend fun sendReminder(chatId: Long, text: String, stopToken: String): Long? {
        send(chatId, text)
        return null
    }
```

Then override it in `TelegramNotifier`:

```kotlin
    override suspend fun sendReminder(chatId: Long, text: String, stopToken: String): Long? {
        val response = message { text }
            .options { parseMode = ParseMode.HTML }
            .inlineKeyboardMarkup { "🔕 Stop reminders" callback "stop?token=$stopToken" }
            .sendReturning(chatId, bot)
        response.onFailure { if (it.errorCode == 403) throw BotBlockedException(chatId) }
        return response.getOrNull()?.messageId
    }
```

`inlineKeyboardMarkup` and the infix `callback` are members of the vendeli message action / builder — no new imports beyond the existing `message`, `ParseMode`, `onFailure`. `sendReturning(...).getOrNull()` returns the sent `Message?`; `.messageId` is a `Long`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests 'cfpbot.NotifierTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/cfpbot/Notifier.kt src/test/kotlin/cfpbot/NotifierTest.kt
git commit -m "feat: Notifier.sendReminder — Stop button + returns message_id"
```

---

### Task 4: CheckTask skips muted, records reminders, prunes

**Files:**
- Modify: `src/main/kotlin/cfpbot/CheckTask.kt`
- Test: `src/test/kotlin/cfpbot/CheckTaskTest.kt` (add cases; existing cases must still pass)

**Interfaces:**
- Consumes: `confToken` (Task 1); `StateRepository.loadMuted / upsertConfDirectory / recordSentReminder / pruneTokens` (Task 2); `Notifier.sendReminder` (Task 3).
- Produces: no new public surface (behavioural change to `CheckTask.run`).

- [ ] **Step 1: Write the failing test**

Add to `src/test/kotlin/cfpbot/CheckTaskTest.kt` inside its `StringSpec` body. A fake that returns fake message ids per chat so `recordSentReminder` runs:

```kotlin
"skips a conference the chat muted, still delivers to other chats" {
    val ds = memDs("checktask_mute"); runDdl(ds)
    val repo = StateRepository(ds)
    repo.addChat(1L)
    repo.addChat(2L)
    // chat 1 muted KotlinConf ahead of time
    repo.mute(1L, confToken("KotlinConf|5 June 2026"))

    val sent = mutableListOf<Pair<Long, String>>()
    val notifier = object : Notifier {
        override suspend fun send(chatId: Long, text: String) { sent += chatId to text }
        override suspend fun sendReminder(chatId: Long, text: String, stopToken: String): Long? {
            sent += chatId to text
            return chatId * 100 // deterministic fake message id
        }
    }
    val task = CheckTask(sourceReturning(feed), repo, notifier, clock = { LocalDate.of(2026, 6, 1) })

    runBlocking { task.run() }

    sent.map { it.first }.toSet() shouldBe setOf(2L)          // chat 1 suppressed entirely
    // chat 2 got both OPENED + CLOSING_SOON recorded for resolution
    repo.tokenForMessage(2L, 200L) shouldBe confToken("KotlinConf|5 June 2026")
}

"prunes directory/mute/sent rows once a conference has closed" {
    val ds = memDs("checktask_prune"); runDdl(ds)
    val repo = StateRepository(ds)
    repo.addChat(1L)
    val notifier = object : Notifier {
        override suspend fun send(chatId: Long, text: String) {}
        override suspend fun sendReminder(chatId: Long, text: String, stopToken: String) = 55L
    }
    // First run while the conf is open: records directory + sent_reminder, then mute it.
    val open = CheckTask(sourceReturning(feed), repo, notifier, clock = { LocalDate.of(2026, 6, 1) })
    runBlocking { open.run() }
    repo.mute(1L, confToken("KotlinConf|5 June 2026"))
    repo.loadMuted()[1L]!!.isEmpty() shouldBe false

    // Later run after the deadline (5 June 2026): conf is closed -> pruned everywhere.
    val closed = CheckTask(sourceReturning(feed), repo, notifier, clock = { LocalDate.of(2026, 6, 10) })
    runBlocking { closed.run() }

    repo.loadMuted() shouldBe emptyMap()
    repo.mutedConfsFor(1L) shouldBe emptyList()
}
```

Note the existing `memDs` helper is defined at the top of `CheckTaskTest`; reuse it.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'cfpbot.CheckTaskTest'`
Expected: FAIL — muted chat still receives sends / pruning not happening.

- [ ] **Step 3: Implement**

Replace the body of `CheckTask` (`src/main/kotlin/cfpbot/CheckTask.kt`) with:

```kotlin
package cfpbot

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate

class CheckTask(
    private val source: ConferenceSource,
    private val repo: StateRepository,
    private val notifier: Notifier,
    private val clock: () -> LocalDate = { LocalDate.now() },
) {
    private val runLock = Mutex()

    suspend fun run() = runLock.withLock {
        val today = clock()
        val conferences = source.fetch()
        val state = repo.loadState()
        val (reminders, newState) = computeReminders(conferences, state, today)
        val muted = repo.loadMuted()

        val blocked = mutableSetOf<Long>()
        for (reminder in reminders) {
            val token = confToken(confKey(reminder.conference))
            for (chatId in state.chats) {
                if (chatId in blocked) continue
                if (token in (muted[chatId] ?: emptySet())) continue
                if (!deliver(reminder, chatId, token)) blocked += chatId
            }
        }
        repo.saveReminderState(newState.confs)
        // Forget conferences that have closed: their tokens fall out of the open set.
        repo.pruneTokens(newState.confs.keys.mapTo(mutableSetOf()) { confToken(it) })
    }

    // Sends one reminder (text + optional location pin) to one chat. Records the sent message so a
    // reply /stop can map it back to this conference, and upserts the token->name directory.
    // Returns true to keep the chat, false if the chat blocked the bot (403 -> pruned).
    private suspend fun deliver(reminder: Reminder, chatId: Long, token: String): Boolean = try {
        val conf = reminder.conference
        val messageId = notifier.sendReminder(chatId, reminder.render(), token)
        repo.upsertConfDirectory(token, confKey(conf), conf.name)
        if (messageId != null) repo.recordSentReminder(chatId, messageId, token)
        if (conf.hasMap()) {
            val coords = conf.coordinates!!
            notifier.sendLocation(chatId, coords.lat, coords.lon)
        }
        true
    } catch (e: BotBlockedException) {
        repo.removeChat(chatId)
        false
    } catch (e: Exception) {
        System.err.println("cfpbot: send to $chatId failed (${e.javaClass.simpleName})")
        true
    }
}
```

- [ ] **Step 4: Run the whole suite (existing CheckTask cases must still pass)**

Run: `./gradlew test --tests 'cfpbot.CheckTaskTest'`
Expected: PASS — including the 5 pre-existing cases (they call `send` via the default `sendReminder` delegation).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/cfpbot/CheckTask.kt src/test/kotlin/cfpbot/CheckTaskTest.kt
git commit -m "feat: CheckTask skips muted confs, records reminders, prunes closed"
```

---

### Task 5: Handlers — Stop/Resume buttons, /stop, /resume, /muted

**Files:**
- Modify: `src/main/kotlin/cfpbot/Commands.kt` (new handlers)
- Modify: `src/main/kotlin/cfpbot/Main.kt` (register `/muted` in the command menu)
- Test: `src/test/kotlin/cfpbot/SmokeTest.kt` (compile gate) + manual verification steps below.

**Interfaces:**
- Consumes: `Registry.repo` (already set in `Main`); `StateRepository.mute / unmute / tokenForMessage / mutedConfsFor` (Task 2); `Registry.notifier` for replies.
- Produces: framework-invoked handlers (no code calls them directly).

Handlers are thin glue over the Task-2 repository methods (which are unit-tested). Verification here is: the project compiles, the smoke test runs, and the manual checklist passes against a live bot.

- [ ] **Step 1: Add the callback + command handlers**

Append to `src/main/kotlin/cfpbot/Commands.kt`. Add imports at the top as needed:

```kotlin
import eu.vendeli.tgbot.types.component.MessageUpdate
```

Handlers:

```kotlin
// 🔕 Stop button on a reminder: callback data "stop?token=<token>" -> mute this conf for this chat.
@CommandHandler.CallbackQuery(["stop"])
suspend fun stopCallback(token: String, update: ProcessedUpdate, bot: TelegramBot) {
    val chatId = update.getChat().id
    Registry.repo.mute(chatId, token)
    message { "🔕 Muted reminders for this conference. Send /muted to manage." }.send(chatId, bot)
}

// 🔔 Resume button (from the /muted list): callback data "resume?token=<token>".
@CommandHandler.CallbackQuery(["resume"])
suspend fun resumeCallback(token: String, update: ProcessedUpdate, bot: TelegramBot) {
    val chatId = update.getChat().id
    Registry.repo.unmute(chatId, token)
    message { "🔔 Resumed reminders for this conference." }.send(chatId, bot)
}

// Reply /stop to a reminder message -> resolve the conference via sent_reminder, then mute.
@CommandHandler(["/stop"])
suspend fun stopCommand(update: ProcessedUpdate, bot: TelegramBot) {
    toggleFromReply(update, bot, mute = true)
}

@CommandHandler(["/resume"])
suspend fun resumeCommand(update: ProcessedUpdate, bot: TelegramBot) {
    toggleFromReply(update, bot, mute = false)
}

private suspend fun toggleFromReply(update: ProcessedUpdate, bot: TelegramBot, mute: Boolean) {
    val chatId = update.getChat().id
    val repliedId = (update as? MessageUpdate)?.message?.replyToMessage?.messageId
    val token = repliedId?.let { Registry.repo.tokenForMessage(chatId, it) }
    if (token == null) {
        message {
            "Reply this to one of my reminder messages, or use the 🔕 button on a reminder."
        }.send(chatId, bot)
        return
    }
    if (mute) {
        Registry.repo.mute(chatId, token)
        message { "🔕 Muted reminders for this conference. Send /muted to manage." }.send(chatId, bot)
    } else {
        Registry.repo.unmute(chatId, token)
        message { "🔔 Resumed reminders for this conference." }.send(chatId, bot)
    }
}

// /muted -> list this chat's muted conferences, each with a 🔔 Resume button.
@CommandHandler(["/muted"])
suspend fun muted(update: ProcessedUpdate, bot: TelegramBot) {
    val chatId = update.getChat().id
    val confs = Registry.repo.mutedConfsFor(chatId)
    if (confs.isEmpty()) {
        message { "You have not muted any conferences." }.send(chatId, bot)
        return
    }
    message { "🔕 Muted conferences:" }.inlineKeyboardMarkup {
        for ((token, name) in confs) {
            "🔔 Resume $name" callback "resume?token=$token"
            newLine()
        }
    }.send(chatId, bot)
}
```

Notes:
- `token` binds from the `token=` param of the callback data because vendeli's default `parameterValueDelimiter='='` maps `token=<x>` to a parameter named `token`.
- `@CommandHandler.CallbackQuery` auto-answers the callback query before the handler runs (no explicit `answerCallbackQuery` needed).
- `newLine()` puts each Resume button on its own row (vendeli `InlineKeyboardMarkupBuilder`).

- [ ] **Step 2: Register /muted in the command menu**

In `src/main/kotlin/cfpbot/Main.kt`, add one line inside the `setMyCommands { ... }` block (after the `active` line):

```kotlin
        botCommand("muted", "List and un-mute conferences you've muted")
```

(`/stop` and `/resume` are intentionally reply-only and omitted from the menu.)

- [ ] **Step 3: Compile + smoke**

Run: `./gradlew test --tests 'cfpbot.SmokeTest'`
Expected: PASS (this confirms all new handlers compile and the KSP handler processor is satisfied).

Then a full build to be sure the KSP-generated activity registration is happy:

Run: `./gradlew build -x test` then `./gradlew test`
Expected: BUILD SUCCESSFUL, all tests PASS.

- [ ] **Step 4: Manual verification against a live bot**

(Requires `BOT_TOKEN`; load env without echoing per `CLAUDE.md`: `set -a; . ./.env; set +a`.)

1. Trigger a reminder (`/check` after registering, or wait for the daily run). Confirm the message shows a `🔕 Stop reminders` button.
2. Tap the button → expect "🔕 Muted…". Run `/check` again → that conference does NOT arrive; others still do.
3. Send `/muted` → expect the conference listed with a `🔔 Resume` button. Tap it → expect "🔔 Resumed…". `/check` → the reminder returns.
4. Reply `/stop` to a reminder message → expect "🔕 Muted…". Reply `/resume` → expect "🔔 Resumed…".
5. Send `/stop` NOT as a reply → expect the "Reply this to one of my reminder messages…" hint.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/cfpbot/Commands.kt src/main/kotlin/cfpbot/Main.kt
git commit -m "feat: Stop/Resume buttons, reply /stop /resume, and /muted list"
```

---

## Notes for the implementer

- **TDD order matters:** Tasks 1→2→3 are pure/repo and fully unit-tested. Task 4 depends on all three. Task 5 is glue verified by compile + manual steps.
- **Do not** change vendeli's command-parsing delimiters; the callback-data scheme relies on the defaults (`?`, `&`, `=`).
- If `getChat().id` is not available on a callback `ProcessedUpdate` at runtime (it should be — `CallbackQueryUpdate` implements `ChatReference`), fall back to `update.user?.id` for the private-chat case; the bot's reminders are per-chat DM so chat id == user id there.
- Token collisions across different conferences are astronomically unlikely at 12 hex (48 bits); no handling needed.
