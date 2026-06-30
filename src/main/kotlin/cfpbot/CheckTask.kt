package cfpbot

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate

class CheckTask(
    private val source: ConferenceSource,
    private val repo: StateRepository,
    private val notifier: Notifier,
    private val clock: () -> LocalDate = { LocalDate.now() },
) {
    private val runLock = Mutex()

    suspend fun run() = runLock.withLock {
        val today = clock()
        val conferences = source.fetch()
        val state = repo.loadState()
        val (reminders, newState) = computeReminders(conferences, state, today)

        val blocked = mutableSetOf<Long>()
        for (reminder in reminders) {
            for (chatId in state.chats) {
                if (chatId !in blocked && !deliver(reminder, chatId)) blocked += chatId
            }
        }
        repo.saveReminderState(newState.confs)
    }

    // Sends one reminder (text + optional location pin) to one chat. Returns true to keep the
    // chat (success, or a transient failure we just drop), false if the chat blocked the bot —
    // a 403 is permanent, so we prune it and the caller skips it for the rest of the run.
    private suspend fun deliver(reminder: Reminder, chatId: Long): Boolean = try {
        notifier.send(chatId, reminder.render())
        val conf = reminder.conference
        if (conf.hasMap()) {
            val coords = conf.coordinates!!
            notifier.sendLocation(chatId, coords.lat, coords.lon)
        }
        true
    } catch (e: BotBlockedException) {
        repo.removeChat(chatId)
        false
    } catch (e: Exception) {
        System.err.println("cfpbot: send to $chatId failed (${e.javaClass.simpleName})")
        true
    }
}
