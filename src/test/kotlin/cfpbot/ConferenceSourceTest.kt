package cfpbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*

class ConferenceSourceTest : StringSpec({
    "fetch deserializes the conferences array, ignoring unknown keys" {
        val body = """
            [
              {"name":"FOSDEM","link":"https://fosdem.org/2026/","locationName":"Brussels, Belgium",
               "coordinates":{"lat":50.8,"lon":4.3,"countryName":"Belgium"},
               "hybrid":false,"date":"31 January-1 February 2026","cfpLink":"","cfpEndDate":""},
              {"name":"Jfokus","link":"https://www.jfokus.se/","locationName":"Stockholm, Sweden",
               "coordinates":{"lat":59.3,"lon":18.0,"countryName":"Sweden"},
               "hybrid":false,"date":"2-4 February 2026",
               "cfpLink":"https://sessionize.com/jfokus-2026","cfpEndDate":"30 September 2025"}
            ]
        """.trimIndent()
        val engine = MockEngine {
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val source = ConferenceSource(HttpClient(engine), url = "https://example.test/conferences.json")
        val confs = source.fetch()
        confs.size shouldBe 2
        confs[1].name shouldBe "Jfokus"
        confs[1].cfpEndDate shouldBe "30 September 2025"
    }
})
