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
        // An empty-but-successful feed (HTTP 200 with []) is an upstream blip, not "every conference
        // closed". Skipping the run avoids pruneTokens(emptySet) wiping every user's mutes (and
        // saveReminderState wiping reminder_state). Fetch *errors* already throw before this point.
        if (conferences.isEmpty()) return@withLock
        val state = repo.loadState()
        val (reminders, newState) = computeReminders(conferences, state, today)

        broadcast(reminders, state.chats, repo.loadMuted())

        repo.saveReminderState(newState.confs)
        // Forget conferences that have closed: their tokens fall out of the open set.
        repo.pruneTokens(newState.confs.keys.mapTo(mutableSetOf()) { confToken(it) })
    }

    // Delivers each reminder to every registered chat, skipping chats that muted the reminder's
    // conference and chats that have blocked the bot (once a 403 is seen, skip them for this run).
    private suspend fun broadcast(reminders: List<Reminder>, chats: Set<Long>, muted: Map<Long, Set<String>>) {
        val blocked = mutableSetOf<Long>()
        for (reminder in reminders) {
            val token = confToken(confKey(reminder.conference))
            for (chatId in chats) {
                if (chatId in blocked) continue
                if (token in (muted[chatId] ?: emptySet())) continue
                if (!deliver(reminder, chatId, token)) blocked += chatId
            }
        }
    }

    // Sends one reminder (text + optional location pin) to one chat. Records the sent message so a
    // reply /stop can map it back to this conference, and upserts the token->name directory.
    // Returns true to keep the chat, false if the chat blocked the bot (403 -> pruned).
    private suspend fun deliver(reminder: Reminder, chatId: Long, token: String): Boolean = try {
        val conf = reminder.conference
        val messageId = notifier.sendReminder(chatId, reminder.render(), token)
        repo.upsertConfDirectory(token, confKey(conf), conf.name)
        if (messageId != null) repo.recordSentReminder(chatId, messageId, token)
        if (conf.hasMap()) {
            val coords = conf.coordinates!!
            notifier.sendLocation(chatId, coords.lat, coords.lon)
        }
        true
    } catch (_: BotBlockedException) {
        repo.removeChat(chatId)
        false
    } catch (e: Exception) {
        System.err.println("cfpbot: send to $chatId failed (${e.javaClass.simpleName})")
        true
    }
}
