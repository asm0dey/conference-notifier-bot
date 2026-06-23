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

## Secrets

The bot token lives in a gitignored `.env` file. To load it before running:

```bash
set -a; . ./.env; set +a
./gradlew run
```

Never commit `.env` or paste the token into any tracked file.

## How it works

State (registered chats + which reminders have been sent) lives in an embedded H2
database. The daily check is a pure recompute from the live feed plus that state, so
restarts never reset progress and a missed day self-heals on the next run. The schedule
itself is persisted by [db-scheduler](https://github.com/kagkarlsson/db-scheduler),
which also runs any missed execution on startup.

The "CFP opened" notice uses first-seen-open semantics globally — a chat that registers
after a CFP was already seen open will not receive that CFP's "opened" message, but will
still get the daily reminders during its final week.

Run the tests with `./gradlew test`.

## Native image (GraalVM)

Build a standalone native executable (starts fast, no JVM needed to run). Requires a
GraalVM-capable JDK — e.g. [Liberica NIK](https://bell-sw.com/liberica-native-image-kit/)
(`sdk install java 25.0.3.r25-nik`). The build reads `GRAALVM_HOME`/`JAVA_HOME`
(`graalvmNative { toolchainDetection = false }`):

```bash
GRAALVM_HOME=/path/to/liberica-nik ./gradlew nativeCompile
```

Output: `build/native/nativeCompile/cfpbot` (~86 MB). Run it like the jar — same env vars:

```bash
set -a; . ./.env; set +a            # load BOT_TOKEN without printing it
DB_PATH=./data/cfpbot ./build/native/nativeCompile/cfpbot
```

Verified in the native image: boot, H2 + HikariCP, db-scheduler init, KSP command
registration (`/start`, `/check`), and long-polling all run cleanly.

### Reachability metadata

GraalVM needs reflection/serialization hints for telegram-bot's runtime serializer
lookups. They live in `src/main/resources/META-INF/native-image/cfpbot/reachability-metadata.json`,
captured with the native-image tracing agent and committed. This covers the startup +
long-poll paths. The **inbound `/start` / `/check` handling and outbound message-send
paths were not exercised when the metadata was captured** (they need a live Telegram
interaction), so the first time you use them in the native binary you may hit a
`Serializer ... not found` / reflection error. If so, top up the metadata by running the
JVM app under the agent while actually using the bot, then rebuild:

```bash
./gradlew installDist
set -a; . ./.env; set +a
JAVA_HOME=/path/to/liberica-nik \
JAVA_OPTS="-agentlib:native-image-agent=config-merge-dir=src/main/resources/META-INF/native-image/cfpbot" \
  build/install/conference-notifier-bot/bin/conference-notifier-bot
# DM the bot /start and /check (and post in a channel), then Ctrl-C
GRAALVM_HOME=/path/to/liberica-nik ./gradlew nativeCompile
```
