package cfpbot

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDate

private fun memDataSource(name: String): HikariDataSource =
    HikariDataSource(HikariConfig().apply {
        jdbcUrl = "jdbc:h2:mem:$name;DB_CLOSE_DELAY=-1"
        username = "sa"
        password = ""
        maximumPoolSize = 2
    })

class StateRepositoryTest : StringSpec({
    "round-trips chats and reminder state" {
        val ds = memDataSource("roundtrip")
        runDdl(ds)
        val repo = StateRepository(ds)

        repo.addChat(42L)
        repo.addChat(99L)
        repo.addChat(42L) // idempotent
        repo.saveReminderState(
            mapOf(
                "Devoxx|1 August 2026" to ConfState(announcedOpen = true, lastDailyReminder = null),
                "KotlinConf|5 June 2026" to ConfState(announcedOpen = true, lastDailyReminder = LocalDate.of(2026, 6, 1)),
            ),
        )

        val state = repo.loadState()
        state.chats shouldBe setOf(42L, 99L)
        state.confs["Devoxx|1 August 2026"] shouldBe ConfState(announcedOpen = true, lastDailyReminder = null)
        state.confs["KotlinConf|5 June 2026"] shouldBe
            ConfState(announcedOpen = true, lastDailyReminder = LocalDate.of(2026, 6, 1))
    }

    "saveReminderState replaces prior rows and leaves chats intact" {
        val ds = memDataSource("replace")
        runDdl(ds)
        val repo = StateRepository(ds)

        repo.addChat(7L)
        repo.saveReminderState(mapOf("Old|1 May 2026" to ConfState(announcedOpen = true)))
        repo.saveReminderState(mapOf("New|1 July 2026" to ConfState(announcedOpen = true)))

        val state = repo.loadState()
        state.confs.keys shouldBe setOf("New|1 July 2026")
        state.chats shouldBe setOf(7L)
    }
})
