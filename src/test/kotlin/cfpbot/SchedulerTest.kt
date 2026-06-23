package cfpbot

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldNotBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import java.time.LocalTime

class SchedulerTest : StringSpec({

    "scheduler starts and stops cleanly against the DDL schema" {
        val ds = HikariDataSource(HikariConfig().apply {
            jdbcUrl = "jdbc:h2:mem:sched;DB_CLOSE_DELAY=-1"
            username = "sa"
            password = ""
            maximumPoolSize = 2
        })

        runDdl(ds)

        val engine = MockEngine { respond(content = "[]", status = HttpStatusCode.OK) }
        val source = ConferenceSource(HttpClient(engine), url = "https://example.test/feed.json")
        val repo = StateRepository(ds)
        val notifier = Notifier { _, _ -> }
        val check = CheckTask(source, repo, notifier)

        val scheduler = startScheduler(ds, check, LocalTime.of(9, 0))

        // A non-null scheduler means db-scheduler 16 accepted the schema (priority column, etc.)
        // and registered the recurring task without error.
        scheduler shouldNotBe null

        scheduler.stop()
        ds.close()
    }
})
