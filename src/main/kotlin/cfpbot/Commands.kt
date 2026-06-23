package cfpbot

import eu.vendeli.tgbot.TelegramBot
import eu.vendeli.tgbot.annotations.CommandHandler
import eu.vendeli.tgbot.api.message.message
import eu.vendeli.tgbot.types.component.ProcessedUpdate
import eu.vendeli.tgbot.types.component.getChat

// ponytail: process-global singleton so the framework-invoked top-level handler can reach the repo.
// Single-process bot, set once in main(); promote to the framework's ClassManager DI only if handlers multiply.
object Registry {
    lateinit var repo: StateRepository
    lateinit var check: CheckTask
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
