package cfpbot

import java.time.LocalDate
import java.time.temporal.ChronoUnit

const val REMINDER_WINDOW_DAYS: Long = 7

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

private fun Conference.cfpUrl(): String = cfpLink.ifBlank { link }

fun Reminder.render(): String = when (kind) {
    ReminderKind.OPENED -> buildString {
        append("📢 CFP OPEN: ${conference.name}\n")
        if (conference.locationName.isNotBlank()) append("📍 ${conference.locationName}\n")
        if (conference.date.isNotBlank()) append("🗓 ${conference.date}\n")
        append("⏳ CFP closes ${conference.cfpEndDate}\n")
        if (conference.cfpUrl().isNotBlank()) append("➡️ ${conference.cfpUrl()}")
    }.trimEnd()
    ReminderKind.CLOSING_SOON -> buildString {
        val phrase = when (daysLeft) {
            0L -> "closes TODAY"
            1L -> "closes TOMORROW"
            else -> "closes in $daysLeft days"
        }
        append("⏰ CFP $phrase: ${conference.name}\n")
        append("⏳ Deadline ${conference.cfpEndDate}\n")
        if (conference.cfpUrl().isNotBlank()) append("➡️ ${conference.cfpUrl()}")
    }.trimEnd()
}
