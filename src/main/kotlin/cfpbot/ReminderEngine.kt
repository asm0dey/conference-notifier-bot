package cfpbot

import java.time.LocalDate
import java.time.temporal.ChronoUnit

const val REMINDER_WINDOW_DAYS: Long = 7

fun importanceMarker(daysLeft: Long): String = when {
    daysLeft <= 1 -> "🔴"
    daysLeft <= 3 -> "🟠"
    daysLeft <= 7 -> "🟡"
    else -> "🟢"
}

enum class ReminderKind { OPENED, CLOSING_SOON }

data class Reminder(val conference: Conference, val kind: ReminderKind, val daysLeft: Long)

data class ConfState(val announcedOpen: Boolean = false, val lastDailyReminder: LocalDate? = null)

data class BotState(val chats: Set<Long> = emptySet(), val confs: Map<String, ConfState> = emptyMap())

data class EngineResult(val reminders: List<Reminder>, val newState: BotState)

fun confKey(c: Conference): String = "${c.name}|${c.cfpEndDate}"

fun computeReminders(
    conferences: List<Conference>,
    state: BotState,
    today: LocalDate,
): EngineResult {
    val reminders = mutableListOf<Reminder>()
    val newConfs = mutableMapOf<String, ConfState>()

    for (c in conferences) {
        val close = c.cfpClose() ?: continue
        if (close.isBefore(today)) continue // closed -> drop from state (prune)

        val key = confKey(c)
        var cs = state.confs[key] ?: ConfState()
        val daysLeft = ChronoUnit.DAYS.between(today, close)

        if (!cs.announcedOpen) {
            reminders += Reminder(c, ReminderKind.OPENED, daysLeft)
            cs = cs.copy(announcedOpen = true)
        }
        if (daysLeft in 0..REMINDER_WINDOW_DAYS && cs.lastDailyReminder != today) {
            reminders += Reminder(c, ReminderKind.CLOSING_SOON, daysLeft)
            cs = cs.copy(lastDailyReminder = today)
        }
        newConfs[key] = cs
    }

    // newConfs holds only currently-open confs -> closed/removed ones are pruned automatically.
    return EngineResult(reminders, state.copy(confs = newConfs))
}

fun activeReminders(conferences: List<Conference>, today: LocalDate): List<Reminder> =
    conferences.mapNotNull { c ->
        val close = c.cfpClose() ?: return@mapNotNull null
        if (close.isBefore(today)) return@mapNotNull null
        Reminder(c, ReminderKind.OPENED, ChronoUnit.DAYS.between(today, close))
    }

private fun String.htmlEscape(): String =
    replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

private fun Conference.cfpUrl(): String = cfpLink.ifBlank { link }

private fun Conference.locationLine(): String {
    if (locationName.isBlank()) return ""
    val url = mapsUrl()
    return if (url != null) {
        "📍 <a href=\"$url\">${locationName.htmlEscape()}</a>\n"
    } else {
        "📍 ${locationName.htmlEscape()}\n"
    }
}

fun Reminder.render(): String = when (kind) {
    ReminderKind.OPENED -> buildString {
        append("${importanceMarker(daysLeft)} 📢 CFP OPEN: ${conference.name.htmlEscape()}\n")
        append(conference.locationLine())
        if (conference.date.isNotBlank()) append("🗓 ${conference.date.htmlEscape()}\n")
        append("⏳ CFP closes ${conference.cfpEndDate.htmlEscape()}\n")
        if (conference.cfpUrl().isNotBlank()) append("➡️ ${conference.cfpUrl().htmlEscape()}")
    }.trimEnd()
    ReminderKind.CLOSING_SOON -> buildString {
        val phrase = when (daysLeft) {
            0L -> "closes TODAY"
            1L -> "closes TOMORROW"
            else -> "closes in $daysLeft days"
        }
        append("${importanceMarker(daysLeft)} ⏰ CFP $phrase: ${conference.name.htmlEscape()}\n")
        append(conference.locationLine())
        append("⏳ Deadline ${conference.cfpEndDate.htmlEscape()}\n")
        if (conference.cfpUrl().isNotBlank()) append("➡️ ${conference.cfpUrl().htmlEscape()}")
    }.trimEnd()
}
