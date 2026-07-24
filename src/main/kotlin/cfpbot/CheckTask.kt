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

    private enum class Delivery { SENT, BLOCKED, FAILED }

    private data class BroadcastResult(val sent: Int, val failed: Int)

    suspend fun run() = runLock.withLock {
        val today = clock()
        val conferences = source.fetch()
        // An empty-but-successful feed (HTTP 200 with []) is an upstream blip, not "every conference
        // closed". Skipping the run avoids pruneTokens(emptySet) wiping every user's mutes (and
        // saveReminderState wiping reminder_state). Fetch *errors* already throw before this point.
        if (conferences.isEmpty()) return@withLock
        val state = repo.loadState()
        val (reminders, newState) = computeReminders(conferences, state, today)

        val result = broadcast(reminders, state.chats, repo.loadMuted())

        // A run that attempted deliveries but landed zero (a transport/serialization outage hitting
        // every chat) must NOT advance reminder state — computeReminders already flipped
        // announcedOpen/lastDailyReminder in newState, and persisting that marks the reminder "done"
        // even though nobody got it, losing it forever. Leave state untouched so the next run retries.
        if (result.failed > 0 && result.sent == 0) {
            System.err.println(
                "cfpbot: all ${result.failed} send(s) failed this run; leaving reminder state " +
                    "unchanged so the next run retries",
            )
            return@withLock
        }

        repo.saveReminderState(newState.confs)
        // Forget conferences that have closed: their tokens fall out of the open set.
        repo.pruneTokens(newState.confs.keys.mapTo(mutableSetOf()) { confToken(it) })
    }

    // Delivers each reminder to every registered chat, skipping chats that muted the reminder's
    // conference and chats that have blocked the bot (once a 403 is seen, skip them for this run).
    // Tallies successful vs failed sends so run() can tell a systemic outage from routine skips.
    private suspend fun broadcast(reminders: List<Reminder>, chats: Set<Long>, muted: Map<Long, Set<String>>): BroadcastResult {
        val blocked = mutableSetOf<Long>()
        var sent = 0
        var failed = 0
        for (reminder in reminders) {
            val token = confToken(confKey(reminder.conference))
            for (chatId in chats) {
                if (chatId in blocked) continue
                if (token in (muted[chatId] ?: emptySet())) continue
                when (deliver(reminder, chatId, token)) {
                    Delivery.SENT -> sent++
                    Delivery.BLOCKED -> blocked += chatId
                    Delivery.FAILED -> failed++
                }
            }
        }
        return BroadcastResult(sent, failed)
    }

    // Sends one reminder (text + optional location pin) to one chat. Records the sent message so a
    // reply /stop can map it back to this conference, and upserts the token->name directory.
    private suspend fun deliver(reminder: Reminder, chatId: Long, token: String): Delivery = try {
        val conf = reminder.conference
        val messageId = notifier.sendReminder(chatId, reminder.render(), token)
        repo.upsertConfDirectory(token, confKey(conf), conf.name)
        if (messageId != null) repo.recordSentReminder(chatId, messageId, token)
        if (conf.hasMap()) {
            val coords = conf.coordinates!!
            notifier.sendLocation(chatId, coords.lat, coords.lon)
        }
        Delivery.SENT
    } catch (_: BotBlockedException) {
        repo.removeChat(chatId)
        Delivery.BLOCKED
    } catch (e: Exception) {
        // Log the full cause (message + stack), not just the class name: a swallowed
        // "SerializationException" tells you nothing, but "Serializer for class X not found" names
        // the exact missing native-image metadata. -H:+ReportExceptionStackTraces makes it useful.
        System.err.println("cfpbot: send to $chatId failed (${e.javaClass.name}): ${e.message}")
        e.printStackTrace()
        Delivery.FAILED
    }
}
