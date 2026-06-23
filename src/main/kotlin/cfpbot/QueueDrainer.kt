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
                // Each row is a single send: a row with coordinates is a location pin, otherwise a
                // text message. Keeping text and pin in separate rows means a failed pin re-queues
                // only the pin — it never re-sends (duplicates) the text.
                if (item.lat != null && item.lon != null) {
                    notifier.sendLocation(item.chatId, item.lat, item.lon)
                } else {
                    notifier.send(item.chatId, item.text)
                }
            } catch (e: Exception) {
                blocked += item.chatId
                val nextAttempts = item.attempts + 1
                if (nextAttempts < MAX_SEND_ATTEMPTS) {
                    queue.enqueue(item.chatId, item.text, item.lat, item.lon, nextAttempts)
                } else {
                    System.err.println(
                        "cfpbot: dropping queued message to ${item.chatId} after " +
                            "$nextAttempts attempts (${e.javaClass.simpleName})",
                    )
                }
            }
        }
    }
}
