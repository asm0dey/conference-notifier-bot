package cfpbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class NotifierTest : StringSpec({
    "a SAM-lambda Notifier still compiles and its sendLocation defaults to a no-op" {
        val sent = mutableListOf<String>()
        val notifier = Notifier { _, text -> sent += text }
        notifier.send(1L, "hi")
        notifier.sendLocation(1L, 10.0, 20.0) // default no-op, must not throw
        sent shouldBe listOf("hi")
    }

    "default sendReminder delegates to send and returns null" {
        val sent = mutableListOf<Pair<Long, String>>()
        val notifier = Notifier { chatId, text -> sent += chatId to text }
        val id = notifier.sendReminder(9L, "hi", "tok")
        id shouldBe null
        sent shouldBe listOf(9L to "hi")
    }
})
