package cfpbot

import eu.vendeli.tgbot.TelegramBot
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking

// Standalone entry point for the GraalVM tracing agent. Run it on a NIK/GraalVM JDK with
// -agentlib:native-image-agent to record the reflective serializer lookups the real telegram-bot
// send path performs, then merge the output into reachability-metadata.json. See scripts note in
// build.gradle.kts. It deliberately mirrors TelegramNotifierSendTest so both cover the same path.
private const val SENT_MESSAGE_JSON = """
{"ok":true,"result":{"message_id":202,"from":{"id":8724557867,"is_bot":true,
"first_name":"Java Conferences","username":"conference_notifier_bot"},
"chat":{"id":1698969,"first_name":"Test","type":"private"},"date":1784910350,
"text":"CFP OPEN"}}
"""

fun main() = runBlocking {
    val engine = MockEngine {
        respond(
            content = SENT_MESSAGE_JSON,
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
        )
    }
    val bot = TelegramBot("test:token", "cfpbot", HttpClient(engine)) {}
    val notifier = TelegramNotifier(bot)
    notifier.sendReminder(1698969L, "🟢 <b>CFP OPEN</b>", "tok123")
    notifier.send(1698969L, "🟡 <b>closing soon</b>")
    notifier.sendLocation(1698969L, 55.67, 12.56)
    println("agent-driver: exercised send/sendReminder/sendLocation")
}
