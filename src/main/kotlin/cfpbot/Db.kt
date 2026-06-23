package cfpbot

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import javax.sql.DataSource

fun createDataSource(dbPath: String): HikariDataSource =
    HikariDataSource(HikariConfig().apply {
        jdbcUrl = "jdbc:h2:file:$dbPath;AUTO_SERVER=TRUE"
        username = "sa"
        password = ""
        maximumPoolSize = 4
    })

private val SCHEMA = listOf(
    """
    CREATE TABLE IF NOT EXISTS registered_chat (
        chat_id BIGINT PRIMARY KEY
    )
    """.trimIndent(),
    """
    CREATE TABLE IF NOT EXISTS reminder_state (
        conf_key VARCHAR(512) PRIMARY KEY,
        announced_open BOOLEAN NOT NULL,
        last_daily_reminder DATE
    )
    """.trimIndent(),
    """
    CREATE TABLE IF NOT EXISTS send_queue (
        id IDENTITY PRIMARY KEY,
        chat_id BIGINT NOT NULL,
        text CLOB NOT NULL,
        lat DOUBLE PRECISION,
        lon DOUBLE PRECISION,
        attempts INT NOT NULL DEFAULT 0
    )
    """.trimIndent(),
    // db-scheduler 16.x table (H2-compatible). The `priority` column + indexes
    // were added in db-scheduler 16 — canonical schema from the 16.x release.
    """
    CREATE TABLE IF NOT EXISTS scheduled_tasks (
        task_name VARCHAR(255) NOT NULL,
        task_instance VARCHAR(255) NOT NULL,
        task_data BLOB,
        execution_time TIMESTAMP WITH TIME ZONE NOT NULL,
        picked BOOLEAN NOT NULL,
        picked_by VARCHAR(50),
        last_success TIMESTAMP WITH TIME ZONE,
        last_failure TIMESTAMP WITH TIME ZONE,
        consecutive_failures INT,
        last_heartbeat TIMESTAMP WITH TIME ZONE,
        version BIGINT NOT NULL,
        priority SMALLINT,
        PRIMARY KEY (task_name, task_instance)
    )
    """.trimIndent(),
    "CREATE INDEX IF NOT EXISTS execution_time_idx ON scheduled_tasks (execution_time)",
    "CREATE INDEX IF NOT EXISTS last_heartbeat_idx ON scheduled_tasks (last_heartbeat)",
    "CREATE INDEX IF NOT EXISTS priority_execution_time_idx ON scheduled_tasks (priority DESC, execution_time ASC)",
)

fun runDdl(ds: DataSource) {
    ds.connection.use { conn ->
        conn.createStatement().use { st ->
            SCHEMA.forEach { st.execute(it) }
        }
    }
}

class StateRepository(private val ds: DataSource) {

    fun addChat(chatId: Long) {
        ds.connection.use { conn ->
            conn.prepareStatement("MERGE INTO registered_chat (chat_id) VALUES (?)").use { ps ->
                ps.setLong(1, chatId)
                ps.executeUpdate()
            }
        }
    }

    fun loadState(): BotState {
        val chats = mutableSetOf<Long>()
        val confs = mutableMapOf<String, ConfState>()
        ds.connection.use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT chat_id FROM registered_chat").use { rs ->
                    while (rs.next()) chats += rs.getLong("chat_id")
                }
                st.executeQuery(
                    "SELECT conf_key, announced_open, last_daily_reminder FROM reminder_state",
                ).use { rs ->
                    while (rs.next()) {
                        val last = rs.getDate("last_daily_reminder")?.toLocalDate()
                        confs[rs.getString("conf_key")] =
                            ConfState(rs.getBoolean("announced_open"), last)
                    }
                }
            }
        }
        return BotState(chats, confs)
    }

    fun saveReminderState(confs: Map<String, ConfState>) {
        ds.connection.use { conn ->
            conn.autoCommit = false
            try {
                conn.createStatement().use { it.execute("DELETE FROM reminder_state") }
                conn.prepareStatement(
                    "INSERT INTO reminder_state (conf_key, announced_open, last_daily_reminder) VALUES (?, ?, ?)",
                ).use { ps ->
                    confs.forEach { (key, cs) ->
                        ps.setString(1, key)
                        ps.setBoolean(2, cs.announcedOpen)
                        ps.setObject(3, cs.lastDailyReminder?.let(java.sql.Date::valueOf))
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
    }
}
