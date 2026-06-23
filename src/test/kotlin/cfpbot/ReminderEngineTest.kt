package cfpbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
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

    "CLOSING_SOON message says closes tomorrow when daysLeft is 1" {
        val c = conf("KotlinConf", "2 June 2026")
        Reminder(c, ReminderKind.CLOSING_SOON, 1L).render() shouldContain "TOMORROW"
    }

    "OPENED render has marker, name, deadline, and a maps hyperlink when coords exist" {
        val c = Conference(
            name = "Devoxx", cfpLink = "https://cfp/devoxx", cfpEndDate = "1 August 2026",
            locationName = "Antwerp, Belgium", coordinates = Coordinates(51.22, 4.40),
        )
        val text = Reminder(c, ReminderKind.OPENED, 61L).render()
        text shouldContain "🟢"
        text shouldContain "📢 CFP OPEN: Devoxx"
        text shouldContain "1 August 2026"
        text shouldContain "<a href=\"https://maps.google.com/?q=51.22,4.4\">Antwerp, Belgium</a>"
    }
    "render shows a plain location line when there are no coords" {
        val c = Conference(name = "Online Conf", cfpEndDate = "1 August 2026", locationName = "online")
        val text = Reminder(c, ReminderKind.OPENED, 61L).render()
        text shouldContain "📍 online"
        text shouldNotContain "<a href"
    }
    "render html-escapes dynamic text" {
        val c = Conference(name = "A & B <Conf>", cfpEndDate = "1 August 2026")
        Reminder(c, ReminderKind.OPENED, 61L).render() shouldContain "A &amp; B &lt;Conf&gt;"
    }
    "CLOSING_SOON render keeps TODAY/TOMORROW phrasing and red marker near deadline" {
        val c = Conference(name = "KotlinConf", cfpEndDate = "1 June 2026")
        Reminder(c, ReminderKind.CLOSING_SOON, 0L).render() shouldContain "closes TODAY"
        Reminder(c, ReminderKind.CLOSING_SOON, 0L).render() shouldContain "🔴"
        Reminder(c, ReminderKind.CLOSING_SOON, 1L).render() shouldContain "closes TOMORROW"
    }

    "importance marker scales with days until deadline" {
        importanceMarker(0L) shouldBe "🔴"
        importanceMarker(1L) shouldBe "🔴"
        importanceMarker(2L) shouldBe "🟠"
        importanceMarker(3L) shouldBe "🟠"
        importanceMarker(4L) shouldBe "🟡"
        importanceMarker(7L) shouldBe "🟡"
        importanceMarker(8L) shouldBe "🟢"
        importanceMarker(60L) shouldBe "🟢"
    }

    "activeReminders returns one OPENED reminder per open conference, skipping closed/blank" {
        val today = LocalDate.of(2026, 6, 1)
        val open = Conference(name = "Open", cfpEndDate = "5 June 2026")
        val closed = Conference(name = "Closed", cfpEndDate = "1 May 2026")
        val blank = Conference(name = "NoCfp", cfpEndDate = "")
        val result = activeReminders(listOf(open, closed, blank), today)
        result.map { it.conference.name } shouldBe listOf("Open")
        result.single().kind shouldBe ReminderKind.OPENED
        result.single().daysLeft shouldBe 4L
    }
})
