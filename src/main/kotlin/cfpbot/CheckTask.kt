package cfpbot

import java.time.LocalDate

class CheckTask(
    private val source: ConferenceSource,
    private val repo: StateRepository,
    private val notifier: Notifier,
    private val clock: () -> LocalDate = { LocalDate.now() },
) {
    suspend fun run() {
        val today = clock()
        val conferences = source.fetch()
        val state = repo.loadState()
        val (reminders, newState) = computeReminders(conferences, state, today)

        for (reminder in reminders) {
            val text = reminder.render()
            for (chatId in state.chats) {
                notifier.send(chatId, text)
            }
        }
        repo.saveReminderState(newState.confs)
    }
}
