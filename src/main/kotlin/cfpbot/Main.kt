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
