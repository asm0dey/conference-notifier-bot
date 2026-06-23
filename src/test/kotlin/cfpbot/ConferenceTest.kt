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
    "parses coordinates and builds a maps url" {
        val c = Conference(name = "X", coordinates = Coordinates(50.8467, 4.3525))
        c.hasMap() shouldBe true
        c.mapsUrl() shouldBe "https://maps.google.com/?q=50.8467,4.3525"
    }
    "treats 0,0 coordinates as no map (online confs)" {
        Conference(name = "X", coordinates = Coordinates(0.0, 0.0)).hasMap() shouldBe false
        Conference(name = "X", coordinates = Coordinates(0.0, 0.0)).mapsUrl() shouldBe null
    }
    "missing coordinates means no map" {
        Conference(name = "X").hasMap() shouldBe false
        Conference(name = "X").mapsUrl() shouldBe null
    }
})
