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

    "removeChat deletes only the target chat" {
        val ds = memDataSource("remove_chat")
        runDdl(ds)
        val repo = StateRepository(ds)

        repo.addChat(1L)
        repo.addChat(2L)
        repo.removeChat(1L)
        repo.removeChat(1L) // idempotent: removing an absent chat is a no-op

        repo.loadState().chats shouldBe setOf(2L)
    }

    "mute / loadMuted / unmute round-trip" {
        val ds = memDataSource("mute1")
        runDdl(ds)
        val repo = StateRepository(ds)
        repo.mute(1L, "aaa")
        repo.mute(1L, "bbb")
        repo.mute(2L, "aaa")
        repo.loadMuted() shouldBe mapOf(1L to setOf("aaa", "bbb"), 2L to setOf("aaa"))
        repo.mute(1L, "aaa") // idempotent
        repo.loadMuted()[1L] shouldBe setOf("aaa", "bbb")
        repo.unmute(1L, "aaa")
        repo.loadMuted()[1L] shouldBe setOf("bbb")
    }

    "sent_reminder resolves a message back to its token" {
        val ds = memDataSource("mute2")
        runDdl(ds)
        val repo = StateRepository(ds)
        repo.recordSentReminder(7L, 100L, "tok1")
        repo.tokenForMessage(7L, 100L) shouldBe "tok1"
        repo.tokenForMessage(7L, 999L) shouldBe null
        repo.recordSentReminder(7L, 100L, "tok2") // MERGE overwrites same (chat,msg)
        repo.tokenForMessage(7L, 100L) shouldBe "tok2"
    }

    "mutedConfsFor joins directory for names" {
        val ds = memDataSource("mute3")
        runDdl(ds)
        val repo = StateRepository(ds)
        repo.upsertConfDirectory("tok1", "KotlinConf|5 June 2026", "KotlinConf")
        repo.upsertConfDirectory("tok2", "Devoxx|1 July 2026", "Devoxx")
        repo.mute(5L, "tok1")
        repo.mute(5L, "tok2")
        repo.mutedConfsFor(5L).toSet() shouldBe setOf("tok1" to "KotlinConf", "tok2" to "Devoxx")
        repo.mutedConfsFor(6L) shouldBe emptyList()
    }

    "pruneTokens deletes rows for closed confs across all tables" {
        val ds = memDataSource("mute4")
        runDdl(ds)
        val repo = StateRepository(ds)
        repo.upsertConfDirectory("live", "L|2026", "Live")
        repo.upsertConfDirectory("dead", "D|2026", "Dead")
        repo.mute(1L, "live")
        repo.mute(1L, "dead")
        repo.recordSentReminder(1L, 10L, "live")
        repo.recordSentReminder(1L, 11L, "dead")

        repo.pruneTokens(setOf("live"))

        repo.loadMuted()[1L] shouldBe setOf("live")
        repo.tokenForMessage(1L, 11L) shouldBe null      // dead sent_reminder gone
        repo.mutedConfsFor(1L) shouldBe listOf("live" to "Live") // dead directory gone

        repo.pruneTokens(emptySet())                     // empty = prune everything
        repo.loadMuted() shouldBe emptyMap()
    }
})
