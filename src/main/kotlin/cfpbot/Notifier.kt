package cfpbot

import eu.vendeli.tgbot.TelegramBot
import eu.vendeli.tgbot.api.common.location
import eu.vendeli.tgbot.api.message.message
import eu.vendeli.tgbot.types.component.ParseMode

fun interface Notifier {
    suspend fun send(chatId: Long, text: String)

    suspend fun sendLocation(chatId: Long, lat: Double, lon: Double) {
        // Default no-op: keeps existing `Notifier { _, _ -> ... }` SAM lambdas valid; real impls override.
    }
}

class TelegramNotifier(private val bot: TelegramBot) : Notifier {
    override suspend fun send(chatId: Long, text: String) {
        message { text }.options { parseMode = ParseMode.HTML }.send(chatId, bot)
    }

    override suspend fun sendLocation(chatId: Long, lat: Double, lon: Double) {
        location(lat.toFloat(), lon.toFloat()).send(chatId, bot)
    }
}
