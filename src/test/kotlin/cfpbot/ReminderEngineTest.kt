package cfpbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.string.shouldContain
import java.time.LocalDate

class ReminderEngineTest : StringSpec({
    val today = LocalDate.of(2026, 6, 1)
    fun conf(name: String, end: String, link: String = "https://cfp/$name") =
        Conference(name = name, cfpLink = link, cfpEndDate = end)

    "first sighting of an open cfp emits OPENED once and records it" {
        val c = conf("Devoxx", "1 August 2026") // far future, outside window
        val r = computeReminders(listOf(c), BotState(), today)
        r.reminders.map { it.kind } shouldBe listOf(ReminderKind.OPENED)
        r.newState.confs[confKey(c)]!!.announcedOpen shouldBe true
    }

    "already-announced cfp far from close emits nothing" {
        val c = conf("Devoxx", "1 August 2026")
        val state = BotState(confs = mapOf(confKey(c) to ConfState(announcedOpen = true)))
        computeReminders(listOf(c), state, today).reminders shouldBe emptyList()
    }

    "cfp closing within the window emits a daily CLOSING_SOON reminder" {
        val c = conf("KotlinConf", "5 June 2026") // 4 days out
        val state = BotState(confs = mapOf(confKey(c) to ConfState(announcedOpen = true)))
        val r = computeReminders(listOf(c), state, today)
        r.reminders.map { it.kind } shouldBe listOf(ReminderKind.CLOSING_SOON)
        r.reminders.single().daysLeft shouldBe 4L
        r.newState.confs[confKey(c)]!!.lastDailyReminder shouldBe today
    }

    "the same daily reminder is not sent twice on the same day" {
        val c = conf("KotlinConf", "5 June 2026")
        val state = BotState(
            confs = mapOf(confKey(c) to ConfState(announcedOpen = true, lastDailyReminder = today)),
        )
        computeReminders(listOf(c), state, today).reminders shouldBe emptyList()
    }

    "a reminder fires on the close day itself (daysLeft 0)" {
        val c = conf("KotlinConf", "1 June 2026")
        val state = BotState(confs = mapOf(confKey(c) to ConfState(announcedOpen = true)))
        val r = computeReminders(listOf(c), state, today)
        r.reminders.single().kind shouldBe ReminderKind.CLOSING_SOON
        r.reminders.single().daysLeft shouldBe 0L
    }

    "a brand-new cfp already inside the window emits both OPENED and CLOSING_SOON" {
        val c = conf("LastMinute", "3 June 2026")
        val r = computeReminders(listOf(c), BotState(), today)
        r.reminders.map { it.kind }
            .shouldContainExactlyInAnyOrder(ReminderKind.OPENED, ReminderKind.CLOSING_SOON)
    }

    "a cfp whose close date has passed is ignored and pruned from state" {
        val c = conf("Gone", "1 May 2026")
        val state = BotState(confs = mapOf(confKey(c) to ConfState(announcedOpen = true)))
        val r = computeReminders(listOf(c), state, today)
        r.reminders shouldBe emptyList()
        r.newState.confs.containsKey(confKey(c)) shouldBe false
    }

    "a conference with no cfp end date is skipped" {
        val c = Conference(name = "NoCfp", cfpEndDate = "")
        computeReminders(listOf(c), BotState(), today).reminders shouldBe emptyList()
    }

    "registered chats are preserved in new state" {
        val state = BotState(chats = setOf(42L, 99L))
        computeReminders(emptyList(), state, today).newState.chats shouldBe setOf(42L, 99L)
    }

    "next year's cfp (new end date) re-announces as OPENED" {
        val thisYear = conf("Devoxx", "1 August 2026")
        val nextYear = conf("Devoxx", "1 August 2027")
        val state = BotState(confs = mapOf(confKey(thisYear) to ConfState(announcedOpen = true)))
        val r = computeReminders(listOf(nextYear), state, today)
        r.reminders.map { it.kind } shouldBe listOf(ReminderKind.OPENED)
    }

    "OPENED message names the conference and its deadline" {
        val c = conf("Devoxx", "1 August 2026")
        val text = Reminder(c, ReminderKind.OPENED, 61L).render()
        text shouldContain "Devoxx"
        text shouldContain "1 August 2026"
    }

    "CLOSING_SOON message says closes today when daysLeft is 0" {
        val c = conf("KotlinConf", "1 June 2026")
        Reminder(c, ReminderKind.CLOSING_SOON, 0L).render() shouldContain "TODAY"
    }
})
