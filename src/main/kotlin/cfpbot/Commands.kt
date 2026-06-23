package cfpbot

import eu.vendeli.tgbot.TelegramBot
import eu.vendeli.tgbot.annotations.CommandHandler
import eu.vendeli.tgbot.api.message.message
import eu.vendeli.tgbot.types.component.ProcessedUpdate
import eu.vendeli.tgbot.types.component.getChat
import java.time.LocalDate

// ponytail: process-global singleton so the framework-invoked top-level handlers can reach
// dependencies. Single-process bot, set once in main().
object Registry {
    lateinit var repo: StateRepository
    lateinit var check: CheckTask
    lateinit var source: ConferenceSource
    lateinit var notifier: Notifier
    lateinit var queue: SendQueueRepository
    lateinit var drainer: QueueDrainer
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

@CommandHandler(["/check"])
suspend fun check(update: ProcessedUpdate, bot: TelegramBot) {
    val chat = update.getChat()
    Registry.check.run()
    message { "🔄 Ran the CFP check now." }.send(chat.id, bot)
}

@CommandHandler(["/active"])
suspend fun active(update: ProcessedUpdate, bot: TelegramBot) {
    val chat = update.getChat()
    val reminders = activeReminders(Registry.source.fetch(), LocalDate.now())
    if (reminders.isEmpty()) {
        Registry.notifier.send(chat.id, "No active CFPs right now.")
        return
    }
    for (reminder in reminders) {
        // Don't let one failed/rate-limited send (429) abort the listing with a stack trace —
        // drop it and continue. /active is on-demand, so re-run to retry.
        runCatching { Registry.notifier.send(chat.id, reminder.render()) }
            .onFailure { System.err.println("cfpbot: /active send failed (${it.javaClass.simpleName})") }
        val conf = reminder.conference
        if (conf.hasMap()) {
            val coords = conf.coordinates!!
            runCatching { Registry.notifier.sendLocation(chat.id, coords.lat, coords.lon) }
                .onFailure { System.err.println("cfpbot: /active location failed (${it.javaClass.simpleName})") }
        }
    }
}
