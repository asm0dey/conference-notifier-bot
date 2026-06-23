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
suspend fun active(update: ProcessedUpdate) {
    val chat = update.getChat()
    val reminders = activeReminders(Registry.source.fetch(), LocalDate.now())
    if (reminders.isEmpty()) {
        Registry.notifier.send(chat.id, "No active CFPs right now.")
        return
    }
    // Enqueue one message per open CFP, then drain ASAP. A per-chat rate limit during the drain
    // leaves the remainder queued for the recurring drain-queue task to deliver within ~2 min.
    for (reminder in reminders) {
        val conf = reminder.conference
        val lat = if (conf.hasMap()) conf.coordinates!!.lat else null
        val lon = if (conf.hasMap()) conf.coordinates!!.lon else null
        Registry.queue.enqueue(chat.id, reminder.render(), lat, lon)
    }
    Registry.drainer.drain()
}
