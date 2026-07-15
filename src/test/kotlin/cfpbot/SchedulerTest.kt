package cfpbot

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import java.time.LocalTime
import kotlin.time.Duration.Companion.seconds

@Suppress("unused")
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
        val queue = SendQueueRepository(ds)
        val drainer = QueueDrainer(queue, notifier, repo)

        val scheduler = startScheduler(ds, check, LocalTime.of(9, 0), drainer)

        // A non-null scheduler means db-scheduler 16 accepted the schema (priority column, etc.)
        // and registered the recurring task without error.
        scheduler shouldNotBe null

        // The recurring tasks must actually be *scheduled* — db-scheduler only inserts an initial
        // execution row for tasks passed to startTasks(). If they are only registered as known
        // tasks (create(ds, tasks...)), no row is ever inserted and they never fire. Poll briefly:
        // start() schedules on the scheduler thread, so the row may appear a moment after return.
        fun scheduledCount(name: String): Int =
            ds.connection.use { conn ->
                conn.prepareStatement("SELECT COUNT(*) FROM scheduled_tasks WHERE task_name = ?").use { ps ->
                    ps.setString(1, name)
                    ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
                }
            }

        eventually(5.seconds) {
            scheduledCount("cfp-check") shouldBe 1
            scheduledCount("drain-queue") shouldBe 1
        }

        scheduler.stop()
        ds.close()
    }
})
