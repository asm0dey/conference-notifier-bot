package cfpbot

const val MAX_SEND_ATTEMPTS: Int = 5

class QueueDrainer(
    private val queue: SendQueueRepository,
    private val notifier: Notifier,
) {
    // Drains the queue oldest-first. Telegram limits are per-chat, so a failing chat is added to
    // `blocked` (skipped for the rest of this pass) while other chats keep draining. Failed items
    // are re-queued with an incremented attempt count until the poison cap, then dropped.
    suspend fun drain() {
        val blocked = mutableSetOf<Long>()
        while (true) {
            val item = queue.claimAndRemove(blocked) ?: break
            try {
                notifier.send(item.chatId, item.text)
                if (item.lat != null && item.lon != null) {
                    notifier.sendLocation(item.chatId, item.lat, item.lon)
                }
            } catch (e: Exception) {
                blocked += item.chatId
                // Re-enqueue the failed item plus any remaining items for the same chat so they
                // all advance together and the blocked set stops the loop for this chat.
                val allFailed = listOf(item) + queue.drainChat(item.chatId)
                for (failed in allFailed) {
                    val nextAttempts = failed.attempts + 1
                    if (nextAttempts < MAX_SEND_ATTEMPTS) {
                        queue.enqueue(failed.chatId, failed.text, failed.lat, failed.lon, nextAttempts)
                    } else {
                        System.err.println(
                            "cfpbot: dropping queued message to ${failed.chatId} after " +
                                "$nextAttempts attempts (${e.javaClass.simpleName})",
                        )
                    }
                }
            }
        }
    }
}
