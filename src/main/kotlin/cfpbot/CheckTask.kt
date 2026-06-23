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

        for (reminder in reminders) {
            val text = reminder.render()
            val conf = reminder.conference
            for (chatId in state.chats) {
                // One failing chat (rate-limit / bad id) must not abort the batch and skip the
                // persist below — that would re-send everything next run. Drop the failed message.
                runCatching { notifier.send(chatId, text) }
                    .onFailure { System.err.println("cfpbot: send to $chatId failed (${it.javaClass.simpleName})") }
                if (conf.hasMap()) {
                    val coords = conf.coordinates!!
                    runCatching { notifier.sendLocation(chatId, coords.lat, coords.lon) }
                        .onFailure { System.err.println("cfpbot: location to $chatId failed (${it.javaClass.simpleName})") }
                }
            }
        }
        repo.saveReminderState(newState.confs)
    }
}
