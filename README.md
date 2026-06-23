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

Run the tests with `./gradlew test`.
