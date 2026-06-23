package cfpbot

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.collections.shouldHaveSize
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private fun memDs(name: String) = HikariDataSource(HikariConfig().apply {
    jdbcUrl = "jdbc:h2:mem:$name;DB_CLOSE_DELAY=-1"
    username = "sa"; password = ""; maximumPoolSize = 10
})

class SendQueueRepositoryTest : StringSpec({
    "enqueue then claimAndRemove round-trips and removes the row" {
        val ds = memDs("queue_rt"); runDdl(ds)
        val repo = SendQueueRepository(ds)
        repo.enqueue(7L, "hello", 50.1, 4.2)
        repo.count() shouldBe 1
        val item = repo.claimAndRemove(emptySet())!!
        item.chatId shouldBe 7L
        item.text shouldBe "hello"
        item.lat shouldBe 50.1
        item.lon shouldBe 4.2
        item.attempts shouldBe 0
        repo.count() shouldBe 0
        repo.claimAndRemove(emptySet()).shouldBeNull()
    }

    "claimAndRemove skips chats in the blocked set" {
        val ds = memDs("queue_blocked"); runDdl(ds)
        val repo = SendQueueRepository(ds)
        repo.enqueue(1L, "a", null, null)
        repo.enqueue(2L, "b", null, null)
        // chat 1 blocked -> first claim returns chat 2's row
        repo.claimAndRemove(setOf(1L))!!.chatId shouldBe 2L
        // now only chat 1 remains, still blocked -> null
        repo.claimAndRemove(setOf(1L)).shouldBeNull()
        // unblocked -> returns chat 1
        repo.claimAndRemove(emptySet())!!.chatId shouldBe 1L
    }

    "null coordinates round-trip as null" {
        val ds = memDs("queue_nullcoords"); runDdl(ds)
        val repo = SendQueueRepository(ds)
        repo.enqueue(9L, "no map", null, null)
        val item = repo.claimAndRemove(emptySet())!!
        item.lat.shouldBeNull()
        item.lon.shouldBeNull()
    }

    "concurrent drainers claim distinct rows with no duplicates and no loss" {
        val ds = memDs("queue_concurrency"); runDdl(ds)
        val repo = SendQueueRepository(ds)
        repeat(200) { repo.enqueue(1000L + it, "msg$it", null, null) }

        val threads = 8
        val claimed = ConcurrentLinkedQueue<Long>()
        val pool = Executors.newFixedThreadPool(threads)
        val done = CountDownLatch(threads)
        repeat(threads) {
            pool.submit {
                try {
                    while (true) {
                        val item = repo.claimAndRemove(emptySet()) ?: break
                        Thread.sleep(1) // simulate the network send
                        claimed += item.id
                    }
                } finally {
                    done.countDown()
                }
            }
        }
        done.await(60, TimeUnit.SECONDS)
        pool.shutdownNow()

        val all = claimed.toList()
        all shouldHaveSize 200
        all.toSet() shouldHaveSize 200   // no duplicates
        repo.count() shouldBe 0          // no loss
    }
})
