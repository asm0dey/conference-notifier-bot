# Auto-Register Contacts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every chat that ever messages the bot receives all notifications, with no `/start` opt-in step, until it blocks/deletes the bot (detected via Telegram `403`).

**Architecture:** A single `@UpdateHandler([UpdateType.MESSAGE])` (which vendeli runs in parallel for *every* message update, command or not) upserts the chat into `registered_chat`. `TelegramNotifier` switches from fire-and-forget `.send()` to `.sendReturning(...).onFailure { ... }` so it can read `Response.Failure.errorCode`; on `403` it throws a typed `BotBlockedException(chatId)`. The two send sites (`CheckTask`, `QueueDrainer`) catch that exception and call `StateRepository.removeChat(chatId)` — immediately, with no requeue.

**Tech Stack:** Kotlin 2.x, vendeli telegram-bot 9.5.0, H2 (HikariCP), kagkarlsson db-scheduler, Kotest (StringSpec) on JUnit platform, kotlinx-coroutines.

## Global Constraints

- vendeli telegram-bot version: **9.5.0** (`eu.vendeli:telegram-bot`).
- `.send(chatId, bot)` returns `Unit` (swallows API errors). Use `.sendReturning(chatId, bot)` → `Deferred<Response<T>>` to observe failures.
- `Response.Failure` fields: `errorCode: Int`, `description: String`, `parameters: ResponseParameters`. Blocked/deleted/kicked ⇒ `errorCode == 403`.
- `onFailure` is the extension `eu.vendeli.tgbot.types.component.onFailure` (suspend, Deferred overload).
- Chat accessor: `eu.vendeli.tgbot.types.component.getChatOrNull(update): Chat?` (nullable, no throw).
- Tests: Kotest `StringSpec`, in-memory H2 (`jdbc:h2:mem:<name>;DB_CLOSE_DELAY=-1`), fake `Notifier` via SAM/object; assertions use `shouldBe` / `shouldContain*`.
- 403 is permanent ⇒ prune immediately (no poison cap). Transient (429/timeout/5xx) ⇒ behaviour unchanged.
- Run all tests with: `./gradlew test`. Run one class with `./gradlew test --tests 'cfpbot.<ClassName>'`.

---

### Task 1: `StateRepository.removeChat`

**Files:**
- Modify: `src/main/kotlin/cfpbot/Db.kt` (add method to `StateRepository`, after `addChat` ~line 79)
- Test: `src/test/kotlin/cfpbot/StateRepositoryTest.kt` (add a case)

**Interfaces:**
- Produces: `fun StateRepository.removeChat(chatId: Long)` — deletes only that chat row; idempotent (deleting an absent chat is a no-op).

- [ ] **Step 1: Write the failing test**

Add this case inside the `StateRepositoryTest` spec block in `src/test/kotlin/cfpbot/StateRepositoryTest.kt`:

```kotlin
    "removeChat deletes only the target chat" {
        val ds = memDataSource("remove_chat")
        runDdl(ds)
        val repo = StateRepository(ds)

        repo.addChat(1L)
        repo.addChat(2L)
        repo.removeChat(1L)
        repo.removeChat(1L) // idempotent: removing an absent chat is a no-op

        repo.loadState().chats shouldBe setOf(2L)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'cfpbot.StateRepositoryTest'`
Expected: FAIL — compile error, `removeChat` unresolved reference.

- [ ] **Step 3: Write minimal implementation**

In `src/main/kotlin/cfpbot/Db.kt`, add this method to `StateRepository` directly after the closing brace of `addChat`:

```kotlin
    fun removeChat(chatId: Long) {
        ds.connection.use { conn ->
            conn.prepareStatement("DELETE FROM registered_chat WHERE chat_id = ?").use { ps ->
                ps.setLong(1, chatId)
                ps.executeUpdate()
            }
        }
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests 'cfpbot.StateRepositoryTest'`
Expected: PASS (both existing and new cases).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/cfpbot/Db.kt src/test/kotlin/cfpbot/StateRepositoryTest.kt
git commit -m "feat: StateRepository.removeChat for pruning blocked chats"
```

---

### Task 2: `BotBlockedException` + `TelegramNotifier` 403 detection

**Files:**
- Modify: `src/main/kotlin/cfpbot/Notifier.kt` (add exception class; rewrite `TelegramNotifier.send` / `sendLocation`)

**Interfaces:**
- Produces: `class BotBlockedException(val chatId: Long) : Exception` — thrown by a `Notifier` when Telegram reports `403` for `chatId`. Callers catch it to prune the chat.
- The `Notifier` fun-interface signature is unchanged: `suspend fun send(chatId: Long, text: String)` and `suspend fun sendLocation(chatId: Long, lat: Double, lon: Double)`. Only `TelegramNotifier` (the real impl) changes; SAM/object test fakes stay valid.

**Note:** `TelegramNotifier` talks to the live Telegram API, so it has **no unit test** here (would require a running bot / HTTP mock of vendeli internals). Its 403→exception contract is exercised through the fake notifiers in Tasks 3 and 4. Manual verification is in Task 5.

- [ ] **Step 1: Add the exception type**

In `src/main/kotlin/cfpbot/Notifier.kt`, add at the top level (below the imports, above `fun interface Notifier`):

```kotlin
// Thrown when Telegram reports 403 (bot blocked by the user, kicked from the group, or chat
// deleted) — permanent, so callers prune the chat from registered_chat immediately.
class BotBlockedException(val chatId: Long) : Exception("Telegram 403 for chat $chatId")
```

- [ ] **Step 2: Switch `TelegramNotifier` to `sendReturning` + `onFailure`**

Replace the body of `class TelegramNotifier` in `src/main/kotlin/cfpbot/Notifier.kt` with:

```kotlin
class TelegramNotifier(private val bot: TelegramBot) : Notifier {
    override suspend fun send(chatId: Long, text: String) {
        message { text }.options { parseMode = ParseMode.HTML }
            .sendReturning(chatId, bot)
            .onFailure { if (it.errorCode == 403) throw BotBlockedException(chatId) }
    }

    override suspend fun sendLocation(chatId: Long, lat: Double, lon: Double) {
        location(lat.toFloat(), lon.toFloat())
            .sendReturning(chatId, bot)
            .onFailure { if (it.errorCode == 403) throw BotBlockedException(chatId) }
    }
}
```

Add these imports to the existing import block at the top of the file:

```kotlin
import eu.vendeli.tgbot.types.component.onFailure
```

(`sendReturning` is a member of the action returned by `message {}` / `location()`, so it needs no extra import. `onFailure` here is the `Deferred<Response<T>>` extension in `eu.vendeli.tgbot.types.component`.)

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL. If `onFailure` is unresolved, confirm the import path with:
`javap -cp <telegram-bot-jvm-9.5.0.jar> eu.vendeli.tgbot.types.component.ResponseKt` (the `onFailure(Deferred, Function1, Continuation)` overload is the target).

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/cfpbot/Notifier.kt
git commit -m "feat: TelegramNotifier throws BotBlockedException on Telegram 403"
```

---

### Task 3: `CheckTask` prunes blocked chats

**Files:**
- Modify: `src/main/kotlin/cfpbot/CheckTask.kt` (rewrite the send loop, lines ~21-35)
- Test: `src/test/kotlin/cfpbot/CheckTaskTest.kt` (add a case)

**Interfaces:**
- Consumes: `BotBlockedException(chatId)` (Task 2), `StateRepository.removeChat` (Task 1).
- Behaviour: on `BotBlockedException` for a chat, call `repo.removeChat(chatId)` and skip all remaining sends to that chat in this run. Other exceptions are logged (unchanged). State is still persisted at the end.

- [ ] **Step 1: Write the failing test**

Add this case inside the `CheckTaskTest` spec block in `src/test/kotlin/cfpbot/CheckTaskTest.kt`:

```kotlin
    "removes a chat that has blocked the bot and keeps delivering to others" {
        val ds = memDs("checktask_blocked")
        runDdl(ds)
        val repo = StateRepository(ds)
        repo.addChat(1L)
        repo.addChat(2L)

        val sent = mutableListOf<Long>()
        val notifier = Notifier { chatId, _ ->
            if (chatId == 1L) throw BotBlockedException(1L)
            sent += chatId
        }
        val task = CheckTask(sourceReturning(feed), repo, notifier, clock = { LocalDate.of(2026, 6, 1) })

        runBlocking { task.run() }

        repo.loadState().chats shouldBe setOf(2L)        // chat 1 pruned
        sent.toSet() shouldBe setOf(2L)                  // chat 2 still got delivered
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'cfpbot.CheckTaskTest'`
Expected: FAIL — chat 1 is not removed (current code only logs), so `chats` is `setOf(1L, 2L)`.

- [ ] **Step 3: Rewrite the send loop**

Replace the `for (reminder in reminders) { ... }` loop body in `src/main/kotlin/cfpbot/CheckTask.kt` with:

```kotlin
        val blocked = mutableSetOf<Long>()
        for (reminder in reminders) {
            val text = reminder.render()
            val conf = reminder.conference
            for (chatId in state.chats) {
                if (chatId in blocked) continue // already pruned this run; don't retry
                try {
                    notifier.send(chatId, text)
                    if (conf.hasMap()) {
                        val coords = conf.coordinates!!
                        notifier.sendLocation(chatId, coords.lat, coords.lon)
                    }
                } catch (e: BotBlockedException) {
                    // 403 is permanent: drop the chat so future runs skip it.
                    repo.removeChat(chatId)
                    blocked += chatId
                } catch (e: Exception) {
                    // Transient/other failure: drop just this message, keep the chat, keep the batch.
                    System.err.println("cfpbot: send to $chatId failed (${e.javaClass.simpleName})")
                }
            }
        }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests 'cfpbot.CheckTaskTest'`
Expected: PASS (the new case plus all four existing cases — the happy-path tests are unaffected because no fake throws).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/cfpbot/CheckTask.kt src/test/kotlin/cfpbot/CheckTaskTest.kt
git commit -m "feat: CheckTask prunes chats that blocked the bot (403)"
```

---

### Task 4: `QueueDrainer` prunes blocked chats (no requeue) + wire `repo`

**Files:**
- Modify: `src/main/kotlin/cfpbot/QueueDrainer.kt` (add `repo` param; handle `BotBlockedException`)
- Modify: `src/main/kotlin/cfpbot/Main.kt:39` (pass `repo` into `QueueDrainer`)
- Test: `src/test/kotlin/cfpbot/QueueDrainerTest.kt` (add a case)

**Interfaces:**
- Consumes: `BotBlockedException(chatId)` (Task 2), `StateRepository.removeChat` (Task 1).
- Produces: `QueueDrainer(queue: SendQueueRepository, notifier: Notifier, repo: StateRepository)` — new third constructor param.
- Behaviour: on `BotBlockedException` for an item, call `repo.removeChat(item.chatId)`, add the chat to the in-pass `blocked` set, and **do not** requeue the item (drop it). Transient failures keep the existing requeue/poison-cap path.

- [ ] **Step 1: Write the failing test**

Add this case inside the `QueueDrainerTest` spec block in `src/test/kotlin/cfpbot/QueueDrainerTest.kt`:

```kotlin
    "a 403 prunes the chat and drops its queued item without re-queueing" {
        val ds = drainerDs("drain_blocked"); runDdl(ds)
        val repo = StateRepository(ds)
        repo.addChat(1L)
        val queue = SendQueueRepository(ds)
        queue.enqueue(1L, "blocked text", null, null)

        val notifier = object : Notifier {
            override suspend fun send(chatId: Long, text: String) { throw BotBlockedException(chatId) }
        }

        runBlocking { QueueDrainer(queue, notifier, repo).drain() }

        repo.loadState().chats shouldBe emptySet()   // chat pruned
        queue.count() shouldBe 0                      // item dropped, not re-queued
    }
```

Also update the three existing `QueueDrainer(queue, notifier)` constructions in this file to `QueueDrainer(queue, notifier, repo)`. For each existing case, add a `repo` built from that case's `ds` right after `runDdl(ds)`:

```kotlin
        val repo = StateRepository(ds)
```

and change `QueueDrainer(queue, notifier).drain()` to `QueueDrainer(queue, notifier, repo).drain()`.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'cfpbot.QueueDrainerTest'`
Expected: FAIL — compile error, `QueueDrainer` constructor takes 2 args (the new `repo` arg is unresolved).

- [ ] **Step 3: Add `repo` param and handle `BotBlockedException`**

In `src/main/kotlin/cfpbot/QueueDrainer.kt`, change the constructor:

```kotlin
class QueueDrainer(
    private val queue: SendQueueRepository,
    private val notifier: Notifier,
    private val repo: StateRepository,
) {
```

Then replace the `catch (e: Exception) { ... }` block with two catches:

```kotlin
            } catch (e: BotBlockedException) {
                // 403 is permanent: prune the chat and drop the item (no requeue).
                repo.removeChat(item.chatId)
                blocked += item.chatId
            } catch (e: Exception) {
                blocked += item.chatId
                val nextAttempts = item.attempts + 1
                if (nextAttempts < MAX_SEND_ATTEMPTS) {
                    queue.enqueue(item.chatId, item.text, item.lat, item.lon, nextAttempts)
                } else {
                    System.err.println(
                        "cfpbot: dropping queued message to ${item.chatId} after " +
                            "$nextAttempts attempts (${e.javaClass.simpleName})",
                    )
                }
            }
```

- [ ] **Step 4: Wire `repo` in `Main.kt`**

In `src/main/kotlin/cfpbot/Main.kt`, change line 39 from:

```kotlin
    val drainer = QueueDrainer(queue, notifier)
```

to:

```kotlin
    val drainer = QueueDrainer(queue, notifier, repo)
```

(`repo` is already defined at `Main.kt:18`.)

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew test --tests 'cfpbot.QueueDrainerTest'`
Expected: PASS (new case plus the four existing cases, now using the 3-arg constructor).

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/cfpbot/QueueDrainer.kt src/main/kotlin/cfpbot/Main.kt src/test/kotlin/cfpbot/QueueDrainerTest.kt
git commit -m "feat: QueueDrainer prunes chats that blocked the bot, no requeue"
```

---

### Task 5: Auto-register on any message via `@UpdateHandler`

**Files:**
- Modify: `src/main/kotlin/cfpbot/Commands.kt` (add `registerContact` handler; drop the now-redundant `addChat` from `/start`)

**Interfaces:**
- Consumes: `Registry.repo` (`StateRepository`), `getChatOrNull` (vendeli).
- Produces: a top-level `@UpdateHandler([UpdateType.MESSAGE])` function that upserts the sender's chat on every message update. After this, registration no longer depends on `/start`; `/start` keeps only its greeting reply.

**Note:** vendeli wires `@UpdateHandler` functions through KSP at runtime, and the bot's update dispatch isn't exercised by the unit-test suite, so this handler has **no automated test**. It is verified by (a) the build/KSP picking it up and (b) the manual smoke check in Step 4.

- [ ] **Step 1: Add the auto-register handler**

In `src/main/kotlin/cfpbot/Commands.kt`, add these imports to the existing import block:

```kotlin
import eu.vendeli.tgbot.annotations.UpdateHandler
import eu.vendeli.tgbot.types.component.UpdateType
import eu.vendeli.tgbot.types.component.getChatOrNull
```

Then add this top-level function (e.g. directly above the `start` handler):

```kotlin
// Auto-register every chat that sends any message — command or plain text. vendeli runs
// @UpdateHandler in parallel for every matching update, so there is no opt-in step: first
// contact registers the chat silently. MERGE makes repeat contact a no-op.
@UpdateHandler([UpdateType.MESSAGE])
suspend fun registerContact(update: ProcessedUpdate) {
    update.getChatOrNull()?.id?.let { Registry.repo.addChat(it) }
}
```

- [ ] **Step 2: Drop the redundant `addChat` from `/start`**

In `src/main/kotlin/cfpbot/Commands.kt`, change the `start` handler so it only greets (registration now happens in `registerContact`):

```kotlin
@CommandHandler(["/start"])
suspend fun start(update: ProcessedUpdate, bot: TelegramBot) {
    val chat = update.getChat()
    message {
        "✅ Registered this chat for Java conference CFP notifications.\n" +
            "You'll get a heads-up when a CFP opens and daily reminders in its final week."
    }.send(chat.id, bot)
}
```

- [ ] **Step 3: Build and run the full suite**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL — compiles (KSP registers `registerContact`) and all tests pass. The KSP-generated registry now includes an UpdateType.MESSAGE handler.

- [ ] **Step 4: Manual smoke verification (documented, run after deploy)**

Because the handler depends on live update dispatch, verify against a running bot:

1. Build/run the bot with `BOT_TOKEN` set.
2. From a Telegram account that is **not** yet registered, send any message that is **not** `/start` — e.g. `/check`, or even plain text.
3. Confirm the chat now appears in `registered_chat` (stop the bot, then):
   ```bash
   docker run --rm -v "$DB_DIR:/data" --entrypoint sh eclipse-temurin:21-jre -c \
     'cd /tmp && curl -sLo h2.jar https://repo1.maven.org/maven2/com/h2database/h2/2.3.232/h2-2.3.232.jar && java -cp h2.jar org.h2.tools.Shell -url "jdbc:h2:/data/cfpbot" -user sa -sql "SELECT chat_id FROM registered_chat;"'
   ```
   The sender's chat id should be present.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/cfpbot/Commands.kt
git commit -m "feat: auto-register any chat that messages the bot (drop opt-in)"
```

---

## Self-Review

**Spec coverage:**
- "Auto-register on contact, silent" → Task 5 (`@UpdateHandler`, no reply; `/start` greeting kept).
- "`registered_chat` becomes a contact list" → Tasks 1+5 (entry path automatic; `removeChat` for exit).
- "Prune on 403 — CheckTask" → Task 3.
- "Prune on 403 — QueueDrainer, no requeue" → Task 4.
- "Transient unchanged" → Tasks 3 & 4 keep the existing non-403 paths; covered by existing QueueDrainer tests still passing.
- "DB: removeChat" → Task 1.
- "Tests: auto-register / 403 prune / transient kept" → Task 3 + Task 4 cover prune + transient; auto-register is manual (Task 5 note) because the handler is KSP/runtime-wired.

**Placeholder scan:** none — every code step shows the full code.

**Type consistency:** `BotBlockedException(val chatId: Long)` defined in Task 2, caught in Tasks 3/4; `removeChat(chatId: Long)` defined in Task 1, called in Tasks 3/4; `QueueDrainer(queue, notifier, repo)` 3-arg form defined in Task 4 and used in Main.kt + tests; `getChatOrNull`/`UpdateType.MESSAGE` confirmed present in vendeli 9.5.0.
