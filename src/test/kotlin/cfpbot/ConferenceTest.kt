package cfpbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldBeNull
import java.time.LocalDate

class ConferenceTest : StringSpec({
    "parses a single-digit day cfp end date" {
        Conference(name = "X", cfpEndDate = "3 May 2026").cfpClose() shouldBe LocalDate.of(2026, 5, 3)
    }
    "parses a two-digit day cfp end date" {
        Conference(name = "X", cfpEndDate = "30 September 2025").cfpClose() shouldBe LocalDate.of(2025, 9, 30)
    }
    "blank cfp end date yields null" {
        Conference(name = "X", cfpEndDate = "").cfpClose().shouldBeNull()
    }
    "garbage cfp end date yields null" {
        Conference(name = "X", cfpEndDate = "soon-ish").cfpClose().shouldBeNull()
    }
})
