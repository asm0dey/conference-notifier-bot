package cfpbot

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldHaveSize
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import java.time.LocalDate

class CheckTaskTest : StringSpec({
    fun memDs(name: String) = HikariDataSource(HikariConfig().apply {
        jdbcUrl = "jdbc:h2:mem:$name;DB_CLOSE_DELAY=-1"
        username = "sa"; password = ""; maximumPoolSize = 2
    })

    val feed = """
        [{"name":"KotlinConf","link":"https://kotlinconf.com","locationName":"Copenhagen",
          "hybrid":false,"date":"May 2026","cfpLink":"https://cfp/kotlin","cfpEndDate":"5 June 2026"}]
    """.trimIndent()

    fun sourceReturning(body: String): ConferenceSource {
        val engine = MockEngine { respond(content = body, status = HttpStatusCode.OK) }
        return ConferenceSource(HttpClient(engine), url = "https://example.test/feed.json")
    }

    "sends a reminder to every registered chat and persists state" {
        val ds = memDs("checktask1")
        runDdl(ds)
        val repo = StateRepository(ds)
        repo.addChat(1L)
        repo.addChat(2L)

        val sent = mutableListOf<Pair<Long, String>>()
        val notifier = Notifier { chatId, text -> sent += chatId to text }
        val task = CheckTask(sourceReturning(feed), repo, notifier, clock = { LocalDate.of(2026, 6, 1) })

        runBlocking { task.run() }

        // One OPENED + one CLOSING_SOON (4 days out), each to 2 chats = 4 sends.
        sent shouldHaveSize 4
        sent.map { it.first }.toSet() shouldBe setOf(1L, 2L)
        repo.loadState().confs["KotlinConf|5 June 2026"]!!.announcedOpen shouldBe true
    }

    "running again the same day sends nothing new" {
        val ds = memDs("checktask2")
        runDdl(ds)
        val repo = StateRepository(ds)
        repo.addChat(1L)

        val sent = mutableListOf<Pair<Long, String>>()
        val notifier = Notifier { chatId, text -> sent += chatId to text }
        val task = CheckTask(sourceReturning(feed), repo, notifier, clock = { LocalDate.of(2026, 6, 1) })

        runBlocking { task.run() }
        val afterFirst = sent.size
        runBlocking { task.run() }
        sent.size shouldBe afterFirst
    }

    "sends a native location pin for conferences that have coordinates" {
        val ds = memDs("checktask_loc")
        runDdl(ds)
        val repo = StateRepository(ds)
        repo.addChat(1L)

        val locations = mutableListOf<Triple<Long, Double, Double>>()
        val notifier = object : Notifier {
            override suspend fun send(chatId: Long, text: String) {}
            override suspend fun sendLocation(chatId: Long, lat: Double, lon: Double) {
                locations += Triple(chatId, lat, lon)
            }
        }
        val feedWithCoords = """
            [{"name":"KotlinConf","cfpLink":"https://cfp","cfpEndDate":"5 June 2026",
              "locationName":"Copenhagen","coordinates":{"lat":55.67,"lon":12.56}}]
        """.trimIndent()
        val task = CheckTask(sourceReturning(feedWithCoords), repo, notifier,
            clock = { LocalDate.of(2026, 6, 1) })

        runBlocking { task.run() }

        // OPENED + CLOSING_SOON both reference the same conf; both fire a pin to chat 1.
        locations.map { it.first }.toSet() shouldBe setOf(1L)
        locations.first().second shouldBe 55.67
        locations.first().third shouldBe 12.56
    }

    "sends no location pin when the conference has no coordinates" {
        val ds = memDs("checktask_noloc")
        runDdl(ds)
        val repo = StateRepository(ds)
        repo.addChat(1L)

        val locations = mutableListOf<Triple<Long, Double, Double>>()
        val notifier = object : Notifier {
            override suspend fun send(chatId: Long, text: String) {}
            override suspend fun sendLocation(chatId: Long, lat: Double, lon: Double) {
                locations += Triple(chatId, lat, lon)
            }
        }
        // `feed` (no coordinates) is the existing top-level val in this test file.
        val task = CheckTask(sourceReturning(feed), repo, notifier,
            clock = { LocalDate.of(2026, 6, 1) })

        runBlocking { task.run() }

        locations shouldBe emptyList()
    }

    "removes a chat that has blocked the bot and keeps delivering to others" {
        val ds = memDs("checktask_blocked")
        runDdl(ds)
        val repo = StateRepository(ds)
        repo.addChat(1L)
        repo.addChat(2L)

        val sent = mutableListOf<Long>()
        val notifier = Notifier { chatId, _ ->
            if (chatId == 1L) throw BotBlockedException(1L)
            sent += chatId
        }
        val task = CheckTask(sourceReturning(feed), repo, notifier, clock = { LocalDate.of(2026, 6, 1) })

        runBlocking { task.run() }

        repo.loadState().chats shouldBe setOf(2L)        // chat 1 pruned
        sent.toSet() shouldBe setOf(2L)                  // chat 2 still got delivered
    }
})
