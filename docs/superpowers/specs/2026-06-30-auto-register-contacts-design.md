# Auto-Register Contacts (Drop the Opt-In Model)

**Date:** 2026-06-30
**Status:** Approved, pending implementation plan

## Problem

The bot only sends notifications to chats in `registered_chat`, and the only
way into that table is the explicit `/start` command. A user who opened the bot
but never sent (or whose `/start` predated a DB reset) silently receives
nothing — which is exactly what happened: zero notifications ever, because the
chat was never registered.

The owner's expectation: **once the app runs, every chat that has ever contacted
the bot receives all notifications — no subscription step — until that chat
blocks or deletes the bot.**

## Hard Constraint

Telegram bots cannot push to a chat that never messaged the bot — there is no
`chat_id` until first contact. So a `chat_id` store is unavoidable. The change
is in *how* a chat enters the store (automatic on first contact, not an explicit
opt-in) and *when* it leaves (the bot is blocked/deleted, detected via `403`).

## Design

### 1. Auto-register on contact

A central catch-all handler upserts the chat's `chat_id` on **every** incoming
update — slash command or plain text — via the existing
`StateRepository.addChat` (idempotent `MERGE`). Registration is **silent**: no
reply is sent on first contact.

The three existing command handlers (`/start`, `/check`, `/active`) keep their
current replies. `/start` remains as a friendly greeting but is no longer
required for registration or special in any way.

`setMyCommands` continues to advertise `/start /check /active` unchanged.

**Implementation note (verify at planning time):** the catch-all is the vendeli
common/unprocessed handler. Confirm against current vendeli telegram-bot docs
which handler type fires for *every* update (including ones already matched by a
command handler) — if no single handler covers both, register inside each
command handler **plus** an unprocessed handler for non-command messages so no
contact path is missed.

**Rejected alternative:** registering only inside the three command handlers —
misses plain-text messages, so "any contact registers" would not hold.

### 2. `registered_chat` becomes a contact list

The table and schema are unchanged. Semantically it is now an automatic address
book of every chat that has contacted the bot, not an opt-in subscriber list.
No code anywhere gates notifications behind a subscribe flag — chats already
receive reminders purely by being in this table; only the entry path changes.

### 3. Prune on 403 (blocked / deleted)

`Notifier.send` and `Notifier.sendLocation` must detect a Telegram
`403 Forbidden` response (bot blocked by user, kicked from group, or chat
deleted) and surface it to callers distinctly from transient failures.

- `CheckTask` (currently `runCatching` around send at `CheckTask.kt:27,31`): on
  `403`, call `repo.removeChat(chatId)` and skip the remaining sends for that
  chat in this run.
- `QueueDrainer` (currently catches `Exception` at `QueueDrainer.kt:25`): on
  `403`, call `removeChat(chatId)` and **do not** requeue the item.
- Transient failures (`429` rate limit, connect/read timeout, `5xx`): behaviour
  **unchanged** — transport-layer retry (`maxRequestRetry`) then existing
  requeue/poison-cap logic in `QueueDrainer`, drop-and-log in `CheckTask`.

`403` is permanent, so the chat is removed immediately (no poison cap).

**Implementation note (verify at planning time):** vendeli's `.send()` returns a
`Response` and does not throw on API errors. Confirm the exact shape of the
failure variant (e.g. `Response.Failure` and where `error_code` lives) so the
notifier can map `403` to a typed signal — a dedicated exception
(e.g. `BotBlockedException(chatId)`) thrown from the notifier is the cleanest way
to let `runCatching`/`catch` sites distinguish it. Transient errors must **not**
map to this signal.

### 4. Database

Add `StateRepository.removeChat(chatId: Long)` — a single
`DELETE FROM registered_chat WHERE chat_id = ?`. `registered_chat` schema is
unchanged.

## Testing (Kotest)

- **Auto-register:** an incoming update from a new chat results in that
  `chat_id` present in `registered_chat`; a second update from the same chat
  does not error (idempotent).
- **Prune on 403:** a send that fails with the `403` signal results in
  `removeChat` being called; in `QueueDrainer` the item is **not** requeued.
- **Transient kept:** a send that fails with a `429`/timeout signal does **not**
  remove the chat and **does** requeue (in `QueueDrainer`) / is dropped-and-logged
  (in `CheckTask`) — i.e. existing behaviour preserved.
- `removeChat` deletes only the target chat, leaves others intact.

## Out of Scope / Untouched

Reminder engine (`computeReminders`, windows, markers), scheduler, conference
source/fetch, message rendering, queue mechanics (separate text/pin rows,
poison cap), and `setMyCommands` contents.
