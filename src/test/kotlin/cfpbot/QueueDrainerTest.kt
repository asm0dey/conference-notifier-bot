package cfpbot

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import kotlinx.coroutines.runBlocking

private fun drainerDs(name: String) = HikariDataSource(HikariConfig().apply {
    jdbcUrl = "jdbc:h2:mem:$name;DB_CLOSE_DELAY=-1"
    username = "sa"; password = ""; maximumPoolSize = 4
})

class QueueDrainerTest : StringSpec({
    "drains every queued item when all sends succeed" {
        val ds = drainerDs("drain_ok"); runDdl(ds)
        val queue = SendQueueRepository(ds)
        queue.enqueue(1L, "a", null, null)
        queue.enqueue(1L, "b", 10.0, 20.0)
        queue.enqueue(2L, "c", null, null)

        val sent = mutableListOf<String>()
        val pins = mutableListOf<Pair<Double, Double>>()
        val notifier = object : Notifier {
            override suspend fun send(chatId: Long, text: String) { sent += text }
            override suspend fun sendLocation(chatId: Long, lat: Double, lon: Double) { pins += lat to lon }
        }

        runBlocking { QueueDrainer(queue, notifier).drain() }

        sent shouldContainExactlyInAnyOrder listOf("a", "b", "c")
        pins shouldContainExactlyInAnyOrder listOf(10.0 to 20.0)
        queue.count() shouldBe 0
    }

    "a failing chat is backed off and its items re-queued, while other chats still drain" {
        val ds = drainerDs("drain_block"); runDdl(ds)
        val queue = SendQueueRepository(ds)
        queue.enqueue(1L, "fail-1", null, null) // chat 1 will fail
        queue.enqueue(1L, "fail-2", null, null) // chat 1 again
        queue.enqueue(2L, "ok", null, null)     // chat 2 succeeds

        val sent = mutableListOf<String>()
        val notifier = object : Notifier {
            override suspend fun send(chatId: Long, text: String) {
                if (chatId == 1L) throw RuntimeException("429")
                sent += text
            }
        }

        runBlocking { QueueDrainer(queue, notifier).drain() }

        sent shouldBe listOf("ok")          // chat 2 delivered despite chat 1 failing
        queue.count() shouldBe 2            // chat 1's two items re-queued
        // re-queued items carry attempts = 1
        val item = queue.claimAndRemove(emptySet())!!
        item.chatId shouldBe 1L
        item.attempts shouldBe 1
    }

    "an item is dropped (not re-queued) once it reaches the attempt cap" {
        val ds = drainerDs("drain_poison"); runDdl(ds)
        val queue = SendQueueRepository(ds)
        // already at MAX-1 attempts: the next failure hits the cap and drops it
        queue.enqueue(1L, "poison", null, null, attempts = MAX_SEND_ATTEMPTS - 1)

        val notifier = object : Notifier {
            override suspend fun send(chatId: Long, text: String) { throw RuntimeException("429") }
        }

        runBlocking { QueueDrainer(queue, notifier).drain() }

        queue.count() shouldBe 0            // dropped, not re-queued
    }
})
