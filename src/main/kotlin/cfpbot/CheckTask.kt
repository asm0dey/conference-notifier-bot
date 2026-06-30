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
            val text = reminder.render()
            val conf = reminder.conference
            for (chatId in state.chats) {
                if (chatId in blocked) continue // already pruned this run; don't retry
                try {
                    notifier.send(chatId, text)
                    if (conf.hasMap()) {
                        val coords = conf.coordinates!!
                        notifier.sendLocation(chatId, coords.lat, coords.lon)
                    }
                } catch (e: BotBlockedException) {
                    // 403 is permanent: drop the chat so future runs skip it.
                    repo.removeChat(chatId)
                    blocked += chatId
                } catch (e: Exception) {
                    // Transient/other failure: drop just this message, keep the chat, keep the batch.
                    System.err.println("cfpbot: send to $chatId failed (${e.javaClass.simpleName})")
                }
            }
        }
        repo.saveReminderState(newState.confs)
    }
}
