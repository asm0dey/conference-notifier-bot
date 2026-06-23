package cfpbot

import eu.vendeli.tgbot.TelegramBot
import eu.vendeli.tgbot.api.message.message

fun interface Notifier {
    suspend fun send(chatId: Long, text: String)
}

class TelegramNotifier(private val bot: TelegramBot) : Notifier {
    override suspend fun send(chatId: Long, text: String) {
        message { text }.send(chatId, bot)
    }
}
