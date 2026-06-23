package cfpbot

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class RegistryTest : StringSpec({
    "Registry exposes the repo used to register a chat" {
        val ds = HikariDataSource(HikariConfig().apply {
            jdbcUrl = "jdbc:h2:mem:registry;DB_CLOSE_DELAY=-1"
            username = "sa"; password = ""; maximumPoolSize = 2
        })
        runDdl(ds)
        Registry.repo = StateRepository(ds)

        Registry.repo.addChat(555L)

        Registry.repo.loadState().chats shouldBe setOf(555L)
    }
})
