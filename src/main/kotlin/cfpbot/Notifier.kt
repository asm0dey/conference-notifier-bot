package cfpbot

import eu.vendeli.tgbot.TelegramBot
import eu.vendeli.tgbot.api.common.location
import eu.vendeli.tgbot.api.message.message
import eu.vendeli.tgbot.types.component.ParseMode
import eu.vendeli.tgbot.types.component.getOrNull
import eu.vendeli.tgbot.types.component.onFailure

// Thrown when Telegram reports 403 (bot blocked by the user, kicked from the group, or chat
// deleted) — permanent, so callers prune the chat from registered_chat immediately.
class BotBlockedException(val chatId: Long) : Exception("Telegram 403 for chat $chatId")

fun interface Notifier {
    suspend fun send(chatId: Long, text: String)

    suspend fun sendLocation(chatId: Long, lat: Double, lon: Double) {
        // Default no-op: keeps existing `Notifier { _, _ -> ... }` SAM lambdas valid; real impls override.
    }

    // Sends a reminder carrying a "🔕 Stop reminders" inline button and returns the Telegram
    // message_id (so callers can map it back to a conference for reply /stop). Default delegates
    // to send() and returns null — fakes and non-Telegram impls need no button/id.
    suspend fun sendReminder(chatId: Long, text: String, stopToken: String): Long? {
        send(chatId, text)
        return null
    }
}

class TelegramNotifier(private val bot: TelegramBot) : Notifier {
    override suspend fun send(chatId: Long, text: String) {
        message { text }.options { parseMode = ParseMode.HTML }
            .sendReturning(chatId, bot)
            .onFailure { if (it.errorCode == 403) throw BotBlockedException(chatId) }
    }

    override suspend fun sendLocation(chatId: Long, lat: Double, lon: Double) {
        location(lat.toFloat(), lon.toFloat())
            .sendReturning(chatId, bot)
            .onFailure { if (it.errorCode == 403) throw BotBlockedException(chatId) }
    }

    override suspend fun sendReminder(chatId: Long, text: String, stopToken: String): Long? {
        val response = message { text }
            .options { parseMode = ParseMode.HTML }
            .inlineKeyboardMarkup { "🔕 Stop reminders" callback "stop?token=$stopToken" }
            .sendReturning(chatId, bot)
        response.onFailure { if (it.errorCode == 403) throw BotBlockedException(chatId) }
        return response.getOrNull()?.messageId
    }
}
