package cfpbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking

class NotifierTest : StringSpec({
    "a SAM-lambda Notifier still compiles and its sendLocation defaults to a no-op" {
        val sent = mutableListOf<String>()
        val notifier = Notifier { _, text -> sent += text }
        runBlocking {
            notifier.send(1L, "hi")
            notifier.sendLocation(1L, 10.0, 20.0) // default no-op, must not throw
        }
        sent shouldBe listOf("hi")
    }
})
