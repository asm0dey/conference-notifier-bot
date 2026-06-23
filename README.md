# Conference CFP Notifier Bot

A Telegram bot that watches [javaconferences.org](https://javaconferences.org/conferences.json)
and tells you (in a DM or a channel) when conference Call-for-Papers deadlines are coming up,
so you don't miss a submission window.

## What it sends

Two kinds of **push** notifications, delivered as you go:

- **CFP opened** — a one-time heads-up the first time a conference's CFP is seen open.
- **Closing soon** — a reminder every day during the final 7 days before the CFP closes,
  through the close day itself.

Each message carries a **deadline-urgency marker** and, when the conference has coordinates,
a **clickable Google-Maps location** plus a **native Telegram map pin**:

```
🔴 ⏰ CFP closes TODAY: KotlinConf
📍 Copenhagen, Denmark          ← tap → Google Maps (a map pin also arrives)
⏳ Deadline 5 June 2026
➡️ https://sessionize.com/kotlinconf
```

| Marker | Meaning            |
|--------|--------------------|
| 🔴     | closes today / tomorrow |
| 🟠     | 2–3 days left      |
| 🟡     | 4–7 days left      |
| 🟢     | more than a week   |

## Commands

| Command  | What it does |
|----------|--------------|
| `/start` | Registers the current chat for notifications (works in a DM and in a channel). |
| `/check` | Runs the deadline check immediately (handy for verifying wiring). |
| `/active` | Sends a separate message for **every** currently-open CFP, right now. |

`/active` is a **pull** view — your source of truth for "what's open?" — to complement the
timely pushes. It enqueues one message per open CFP and delivers them reliably even past
Telegram's per-chat rate limit (see [How it works](#how-it-works)).

## Setup

1. Create a bot with [@BotFather](https://t.me/BotFather) and copy the token.
2. Put the token in a gitignored `.env` (see [Secrets](#secrets)) and run:

   ```bash
   set -a; . ./.env; set +a
   ./gradlew run
   ```

3. **Register a private chat:** DM your bot `/start`.
4. **Register a channel:** add the bot to the channel as an admin, then post `/start`
   in the channel. The bot stores the channel's chat id and posts there.

## Configuration (env vars)

| Variable     | Default          | Meaning |
|--------------|------------------|---------|
| `BOT_TOKEN`  | (required)       | Telegram bot token from @BotFather |
| `DB_PATH`    | `./data/cfpbot`  | H2 file path (state + scheduler survive restarts) |
| `CHECK_HOUR` | `9`              | Hour of day (0–23, server local time) for the daily check |

## Secrets

The bot token lives in a gitignored `.env` file. Load it without printing it:

```bash
set -a; . ./.env; set +a
./gradlew run
```

Never commit `.env` (or `.envrc`) or paste the token into any tracked file. Telegram API
error URLs embed the token, so the bot redacts errors to the exception class name only.

## How it works

State (registered chats + which reminders have been sent) lives in an embedded **H2**
database. The daily check is a pure recompute from the live feed plus that state, so
restarts never reset progress and a missed day self-heals on the next run. The schedule
is persisted by [db-scheduler](https://github.com/kagkarlsson/db-scheduler), which also
runs any missed execution on startup.

**First-seen-open semantics:** the "CFP opened" notice fires once, the first time a CFP is
seen open. A chat that registers *after* a CFP was already open won't get that CFP's opened
message — but still gets the final-week daily reminders, and `/active` always lists everything
currently open.

**`/active` send queue.** `/active` can produce dozens of messages, and Telegram rate-limits
~1 message/sec **per chat**. So instead of fire-and-forget, `/active` writes each message to a
persisted `send_queue` table and drains it:

- Items are claimed atomically with `FOR UPDATE SKIP LOCKED`, so the inline drain and a
  recurring drain never double-send (even though they can run concurrently).
- On a per-chat rate limit, only that chat is backed off; other chats keep delivering. The
  failing item is re-queued and retried; it's dropped only after 5 real delivery attempts.
- A recurring `drain-queue` task runs every 2 minutes and delivers whatever is left — so the
  remainder arrives within ~2 minutes even if Telegram throttles the first burst.

The timely **push** notifications (opened / closing-today) are sent **directly**, never through
the queue, so an urgent "closes TODAY" is never delayed.

Run the tests with `./gradlew test`.

## Native image (GraalVM)

Build a standalone native executable (starts fast, no JVM needed to run). Requires a
GraalVM-capable JDK — e.g. [Liberica NIK](https://bell-sw.com/liberica-native-image-kit/)
(`sdk install java 25.0.3.r25-nik`). The build reads `GRAALVM_HOME`/`JAVA_HOME`
(`graalvmNative { toolchainDetection = false }`):

```bash
GRAALVM_HOME=/path/to/liberica-nik ./gradlew nativeCompile
```

Output: `build/native/nativeCompile/cfpbot` (~90 MB). Run it like the jar — same env vars:

```bash
set -a; . ./.env; set +a            # load BOT_TOKEN without printing it
DB_PATH=./data/cfpbot ./build/native/nativeCompile/cfpbot
```

Verified in the native image: boot, H2 + HikariCP, db-scheduler (daily check + queue drain),
KSP command registration (`/start`, `/check`, `/active`), and long-polling all run cleanly.

### Reachability metadata

GraalVM needs reflection/serialization hints for telegram-bot's runtime serializer lookups.
They live in `src/main/resources/META-INF/native-image/cfpbot/reachability-metadata.json`,
captured with the native-image tracing agent and committed. This covers the startup +
long-poll paths. The **inbound command handling and outbound message-send paths were not
exercised when the metadata was captured** (they need live Telegram interaction), so the first
time you use them in the native binary you may hit a `Serializer ... not found` / reflection
error. If so, top up the metadata by running the JVM app under the agent while actually using
the bot, then rebuild:

```bash
./gradlew installDist
set -a; . ./.env; set +a
JAVA_HOME=/path/to/liberica-nik \
JAVA_OPTS="-agentlib:native-image-agent=config-merge-dir=src/main/resources/META-INF/native-image/cfpbot" \
  build/install/conference-notifier-bot/bin/conference-notifier-bot
# DM the bot /start, /check, /active (and post in a channel), then Ctrl-C
GRAALVM_HOME=/path/to/liberica-nik ./gradlew nativeCompile
```

## Docker (native, on Alpaquita, non-root)

A multi-stage [`Dockerfile`](Dockerfile) builds a **musl** native binary in a BellSoft
Liberica NIK image and ships it on a tiny `bellsoft/alpaquita-linux-base:stream-musl`
runtime (the binary links musl libc + libz dynamically, both present in that base). The
container runs as a **non-root** user and keeps its H2 database in a mounted `/data` volume.

```bash
docker build -t cfpbot:native .
```

Run — load the token from your local `.env`, bind-mount a DB directory you own, and run as
your own UID/GID so the database files on the host stay owned by you:

```bash
mkdir -p ./data
docker run --rm \
  --env-file .env \
  --user "$(id -u):$(id -g)" \
  -v "$PWD/data:/data" \
  cfpbot:native
```

- `--env-file .env` — secrets stay out of the image; `.env` is also excluded from the build
  context by [`.dockerignore`](.dockerignore).
- `--user "$(id -u):$(id -g)"` + a **bind mount** — the process writes the H2 files as your
  host user, so `./data/cfpbot.mv.db` is owned and readable by you, no root anywhere.
- `DB_PATH` defaults to `/data/cfpbot` inside the container; override with `-e DB_PATH=...`
  (keep it under `/data`). `CHECK_HOUR` defaults to `9`.

Or with Compose ([`compose.yaml`](compose.yaml)) — set your ids once and it wires env, the
volume, and the user:

```bash
UID=$(id -u) GID=$(id -g) DB_DIR=./data docker compose up --build
```

## Tech stack

Kotlin 2.3 / JDK 21 · Gradle (Kotlin DSL) · [vendelieu/telegram-bot](https://github.com/vendelieu/telegram-bot)
+ KSP · Ktor client · kotlinx-serialization · H2 + HikariCP · db-scheduler · Kotest · GraalVM native-image.
