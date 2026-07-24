package cfpbot

import eu.vendeli.tgbot.TelegramBot
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*

// Drives the REAL vendeli send path (serialize request -> deserialize Response<Message>) against a
// mock engine, offline. Two jobs:
//  1) a regression test that TelegramNotifier round-trips a sent message on the current lib version;
//  2) the GraalVM tracing-agent driver — running `-Pagent test` records the exact reflective
//     serializer lookups this path needs, which feed reachability-metadata.json for the native image.
// A realistic Telegram sendMessage response (shape captured from the live Bot API 9.x).
private const val SENT_MESSAGE_JSON = """
{"ok":true,"result":{"message_id":202,"from":{"id":8724557867,"is_bot":true,
"first_name":"Java Conferences","username":"conference_notifier_bot"},
"chat":{"id":1698969,"first_name":"Test","type":"private"},"date":1784910350,
"text":"CFP OPEN"}}
"""

class TelegramNotifierSendTest : StringSpec({
    fun botWithMock(): TelegramBot {
        val engine = MockEngine {
            respond(
                content = SENT_MESSAGE_JSON,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        return TelegramBot("test:token", "cfpbot", HttpClient(engine)) {}
    }

    "sendReminder serializes an HTML message with a stop button and reads back the message id" {
        val notifier = TelegramNotifier(botWithMock())
        val id = notifier.sendReminder(1698969L, "🟢 <b>CFP OPEN</b>", "tok123")
        id shouldBe 202L
    }

    "plain send and sendLocation exercise their serializers without error" {
        val notifier = TelegramNotifier(botWithMock())
        notifier.send(1698969L, "🟡 <b>closing soon</b>")
        notifier.sendLocation(1698969L, 55.67, 12.56)
    }
})
