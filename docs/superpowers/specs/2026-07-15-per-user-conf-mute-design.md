# Per-user, per-conference mute

**Date:** 2026-07-15
**Status:** Approved design, pre-implementation

## Goal

A user can stop reminders about **one specific conference** without affecting
other conferences or other users. Two entry points:

1. **Inline button** `🔕 Stop reminders` attached to every auto-reminder.
2. **Reply command** `/stop` sent as a reply to a reminder message.

Un-mute via a `/muted` list (each row has a `🔔 Resume` button) and via `/resume`
replied to a reminder.

Muting suppresses **all auto-reminders** for that conference for that user — both
the one-time `OPENED` announcement and the daily `CLOSING_SOON` nags. `/active`
(explicit on-demand listing) is **unaffected** and still shows every conference.

## Data model

Three new tables (H2, created in `Db.kt` `SCHEMA`):

```sql
-- token -> conference, so a bare token resolves back to a display name
CREATE TABLE IF NOT EXISTS conf_directory (
    token    VARCHAR(16) PRIMARY KEY,
    conf_key VARCHAR(512) NOT NULL,
    name     VARCHAR(512) NOT NULL
);

-- one row = this chat muted this conference
CREATE TABLE IF NOT EXISTS muted_conf (
    chat_id BIGINT      NOT NULL,
    token   VARCHAR(16) NOT NULL,
    PRIMARY KEY (chat_id, token)
);

-- lets a /stop reply resolve replied-to message_id -> conference token
CREATE TABLE IF NOT EXISTS sent_reminder (
    chat_id    BIGINT      NOT NULL,
    message_id BIGINT      NOT NULL,
    token      VARCHAR(16) NOT NULL,
    PRIMARY KEY (chat_id, message_id)
);
```

**Token** = first 12 hex chars of `SHA-256(conf_key)`, where `conf_key` is the
existing `confKey(c) = "${name}|${cfpEndDate}"`. Deterministic and stable while
`conf_key` is stable, and short enough for Telegram's 64-char callback-data limit
(which the full `name|date` key is not). Added as `fun confToken(confKey: String): String`.

## Flow

### Sending a reminder (CheckTask)

`CheckTask.deliver` currently calls `notifier.send(chatId, text)` (returns `Unit`).
Changes:

- `Notifier.send` returns `Long?` — the sent Telegram `message_id` (null for fakes
  / failures). `TelegramNotifier.send` extracts it via
  `...sendReturning(chatId, bot).getOrNull()?.messageId`.
- A reminder is sent **with** the `🔕 Stop reminders` inline button, callback data
  `stop?token=<token>` (vendeli callback-data param format — verify exact encoding
  during implementation; see Open items).
- After a successful send, `deliver` records `sent_reminder(chatId, messageId, token)`
  and upserts `conf_directory(token, conf_key, name)`.
- `CheckTask.run` loads the muted set once (`Set<Pair<chatId, token>>` or a
  `Map<Long, Set<String>>`) and **skips** `deliver` when `(chatId, token)` is muted.

Only the text message carries the button and is recorded; the optional location
pin is unchanged.

### Stop button

`@CommandHandler.CallbackQuery(["stop"])` handler with a `token` param →
`MERGE INTO muted_conf` → answer "🔕 Muted. Send /muted to manage."

### Reply `/stop` / `/resume`

`@CommandHandler(["/stop"])` / `(["/resume"])`:
- Read the replied-to message id from the update
  (`update`'s message → `replyToMessage.messageId`; verify vendeli accessor).
- Look up `sent_reminder(chat_id, replied_message_id)` → token.
- Found → toggle `muted_conf` (insert for `/stop`, delete for `/resume`), confirm
  with the conf name from `conf_directory`.
- Not found (not a reply, or unknown/expired message) → reply
  "Reply this to a reminder, or use the 🔕 button."

### `/muted` list + Resume button

`@CommandHandler(["/muted"])` → join `muted_conf × conf_directory` for the chat →
one message listing each muted conf with a `🔔 Resume` button, callback data
`resume?token=<token>`. Empty → "Nothing muted."

`@CommandHandler.CallbackQuery(["resume"])` with `token` param → delete
`muted_conf(chatId, token)` → answer "🔔 Resumed." (optionally edit the list message).

### Pruning

In `CheckTask.run`, after `computeReminders` yields the open-conf set, compute the
set of live tokens (`confToken(confKey(c))` for open confs) and delete
`conf_directory`, `muted_conf`, and `sent_reminder` rows whose token is **not**
live. Mirrors the existing `reminder_state` prune — a closed conference is
forgotten everywhere, so mutes for it evaporate.

## Components touched

- `Db.kt` — 3 new tables; `StateRepository` gains: `mute(chatId, token)`,
  `unmute(chatId, token)`, `loadMuted(): Map<Long, Set<String>>`,
  `recordSentReminder(chatId, messageId, token)`, `upsertConfDirectory(token, key, name)`,
  `tokenForMessage(chatId, messageId): String?`, `mutedConfsFor(chatId): List<Pair<token, name>>`,
  `pruneTokens(liveTokens: Set<String>)`.
- `Notifier.kt` — `send` returns `Long?`; new optional `stopButtonToken` param (or a
  dedicated `sendReminder`) so only reminders carry the button. `TelegramNotifier`
  builds the inline keyboard and returns `messageId`.
- `ReminderEngine.kt` — add `confToken(confKey: String): String` (SHA-256, take 12).
- `CheckTask.kt` — load muted set, skip muted `(chatId, token)`, record sent
  reminders + directory, prune tokens.
- `Commands.kt` — new handlers: `/stop`, `/resume`, `/muted`, callback `stop`,
  callback `resume`. `Registry` unchanged (already exposes `repo`).

## Non-goals / YAGNI

- No per-user mute of `/active` output.
- No mute of the location pin independently of its text message.
- No global "mute all" (that's just `/stop` per conf, or blocking the bot).

## Testing

Kotest specs:
- `confToken` deterministic + stable for a key, differs across keys.
- `CheckTask` skips a muted `(chat, conf)` but still delivers to other chats and
  for other confs; still records the OPENED once.
- Repository round-trips: mute/unmute, `loadMuted`, `tokenForMessage`,
  `pruneTokens` removes rows for closed confs across all three tables.
- Reply `/stop` with no matching `sent_reminder` → graceful hint (handler-level).

## Open items to verify during implementation (via context7 / vendeli docs)

1. Exact vendeli **callback-data encoding** so `@CommandHandler.CallbackQuery(["stop"])`
   + a `token` param binds (`stop?token=x` vs other). `@ParamMapping` if needed.
2. Accessor for **replied-to message** from a command's `ProcessedUpdate`
   (`replyToMessage.messageId`) and its type (Int vs Long).
3. `sendReturning(...).getOrNull()?.messageId` type and null-handling.
