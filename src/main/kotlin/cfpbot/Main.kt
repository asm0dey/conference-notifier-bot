package cfpbot

import eu.vendeli.tgbot.TelegramBot
import eu.vendeli.tgbot.api.botactions.setMyCommands
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.delay
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
    val bot = TelegramBot(token) {
        updatesListener {
            updatesPollingTimeout = 30        // server long-poll, seconds
        }
        httpClient {
            requestTimeoutMillis = 45_000L    // must exceed updatesPollingTimeout*1000 + margin
            // Auto-retry transient Telegram 429 (rate limit) at the transport layer, for every
            // request (sends + getUpdates). The per-send runCatching guards then only catch
            // permanent failures (bot blocked, bad chat id) where retrying is futile.
            maxRequestRetry = 3
            retryDelay = 2_000L
            retryStrategy = retryOnTooManyRequests()
        }
    }
    val notifier = TelegramNotifier(bot)
    val queue = SendQueueRepository(ds)
    val drainer = QueueDrainer(queue, notifier, repo)
    val check = CheckTask(source, repo, notifier)
    Registry.check = check
    Registry.source = source
    Registry.notifier = notifier
    Registry.queue = queue
    Registry.drainer = drainer

    // Register the command menu so clients autocomplete /start, /check, /active.
    setMyCommands {
        botCommand("start", "Register this chat for CFP notifications")
        botCommand("check", "Run the CFP check now")
        botCommand("active", "List every currently-open CFP")
    }.send(bot)

    startScheduler(ds, check, runAt, drainer)
    println("cfpbot: scheduler started (daily at $runAt), listening for /start…")

    // ponytail: infinite restart with fixed 5s backoff — a personal bot just needs to stay up.
    while (true) {
        try {
            bot.handleUpdates()
        } catch (e: Exception) {
            System.err.println("cfpbot: update listener error (${e.javaClass.simpleName}); restarting in 5s")
            runCatching { bot.update.stopListener() }
            delay(5_000)
        }
    }
}
