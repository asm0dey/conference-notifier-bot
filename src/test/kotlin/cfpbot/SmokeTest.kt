package cfpbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class SmokeTest : StringSpec({
    "build and test wiring works" {
        (1 + 1) shouldBe 2
    }
})
