package cfpbot

import eu.vendeli.tgbot.TelegramBot
import eu.vendeli.tgbot.annotations.CommandHandler
import eu.vendeli.tgbot.annotations.UpdateHandler
import eu.vendeli.tgbot.api.message.message
import eu.vendeli.tgbot.types.component.*
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

// Auto-register every chat that sends any message — command or plain text. vendeli runs
// @UpdateHandler in parallel for every matching update, so there is no opt-in step: first
// contact registers the chat silently. MERGE makes repeat contact a no-op.
@UpdateHandler([UpdateType.MESSAGE])
suspend fun registerContact(update: ProcessedUpdate) {
    (update as? ChatReference)?.chat?.id?.let { Registry.repo.addChat(it) }
}

@CommandHandler(["/start"])
suspend fun start(update: ProcessedUpdate, bot: TelegramBot) {
    val chat = update.getChat()
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
    // Enqueue the text and the map pin as SEPARATE rows, then drain ASAP. Separate rows mean a
    // rate-limited pin re-queues only the pin (never re-sends the text). A per-chat rate limit
    // during the drain leaves the remainder queued for the recurring drain-queue task (~2 min).
    for (reminder in reminders) {
        Registry.queue.enqueue(chat.id, reminder.render(), null, null)
        val conf = reminder.conference
        if (conf.hasMap()) {
            Registry.queue.enqueue(chat.id, "", conf.coordinates!!.lat, conf.coordinates.lon)
        }
    }
    Registry.drainer.drain()
}

// 🔕 Stop button on a reminder: callback data "stop?token=<token>" -> mute this conf for this chat.
@CommandHandler.CallbackQuery(["stop"], autoAnswer = true)
suspend fun stopCallback(token: String, update: ProcessedUpdate, bot: TelegramBot) {
    val chatId = update.getChat().id
    Registry.repo.mute(chatId, token)
    message { "🔕 Muted reminders for this conference. Send /muted to manage." }.send(chatId, bot)
}

// 🔔 Resume button (from the /muted list): callback data "resume?token=<token>".
@CommandHandler.CallbackQuery(["resume"], autoAnswer = true)
suspend fun resumeCallback(token: String, update: ProcessedUpdate, bot: TelegramBot) {
    val chatId = update.getChat().id
    Registry.repo.unmute(chatId, token)
    message { "🔔 Resumed reminders for this conference." }.send(chatId, bot)
}

// Reply /stop to a reminder message -> resolve the conference via sent_reminder, then mute.
@CommandHandler(["/stop"])
suspend fun stopCommand(update: ProcessedUpdate, bot: TelegramBot) {
    toggleFromReply(update, bot, mute = true)
}

@CommandHandler(["/resume"])
suspend fun resumeCommand(update: ProcessedUpdate, bot: TelegramBot) {
    toggleFromReply(update, bot, mute = false)
}

private suspend fun toggleFromReply(update: ProcessedUpdate, bot: TelegramBot, mute: Boolean) {
    val chatId = update.getChat().id
    val repliedId = (update as? MessageUpdate)?.message?.replyToMessage?.messageId
    val token = repliedId?.let { Registry.repo.tokenForMessage(chatId, it) }
    if (token == null) {
        message {
            "Reply this to one of my reminder messages, or use the 🔕 button on a reminder."
        }.send(chatId, bot)
        return
    }
    if (mute) {
        Registry.repo.mute(chatId, token)
        message { "🔕 Muted reminders for this conference. Send /muted to manage." }.send(chatId, bot)
    } else {
        Registry.repo.unmute(chatId, token)
        message { "🔔 Resumed reminders for this conference." }.send(chatId, bot)
    }
}

// /muted -> list this chat's muted conferences, each with a 🔔 Resume button.
@CommandHandler(["/muted"])
suspend fun muted(update: ProcessedUpdate, bot: TelegramBot) {
    val chatId = update.getChat().id
    val confs = Registry.repo.mutedConfsFor(chatId)
    if (confs.isEmpty()) {
        message { "You have not muted any conferences." }.send(chatId, bot)
        return
    }
    message { "🔕 Muted conferences:" }.inlineKeyboardMarkup {
        for ((token, name) in confs) {
            "🔔 Resume $name" callback "resume?token=$token"
            newLine()
        }
    }.send(chatId, bot)
}
