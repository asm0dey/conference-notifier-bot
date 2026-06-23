package cfpbot

import java.sql.Types
import javax.sql.DataSource

data class QueuedItem(
    val id: Long,
    val chatId: Long,
    val text: String,
    val lat: Double?,
    val lon: Double?,
    val attempts: Int,
)

class SendQueueRepository(private val ds: DataSource) {

    fun enqueue(chatId: Long, text: String, lat: Double?, lon: Double?, attempts: Int = 0) {
        ds.connection.use { conn ->
            conn.prepareStatement(
                "INSERT INTO send_queue (chat_id, text, lat, lon, attempts) VALUES (?, ?, ?, ?, ?)",
            ).use { ps ->
                ps.setLong(1, chatId)
                ps.setString(2, text)
                if (lat != null) ps.setDouble(3, lat) else ps.setNull(3, Types.DOUBLE)
                if (lon != null) ps.setDouble(4, lon) else ps.setNull(4, Types.DOUBLE)
                ps.setInt(5, attempts)
                ps.executeUpdate()
            }
        }
    }

    // Short transaction: claim the oldest non-blocked row (FOR UPDATE SKIP LOCKED), delete it,
    // commit, return it. The send happens AFTER this returns, outside any transaction.
    fun claimAndRemove(blocked: Set<Long>): QueuedItem? {
        val whereClause =
            if (blocked.isEmpty()) ""
            else "WHERE chat_id NOT IN (${blocked.joinToString(",") { "?" }}) "
        // FOR UPDATE SKIP LOCKED must come AFTER LIMIT in H2.
        val claimSql =
            "SELECT id, chat_id, text, lat, lon, attempts FROM send_queue " +
                whereClause + "ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED"

        ds.connection.use { conn ->
            conn.autoCommit = false
            try {
                val item = conn.prepareStatement(claimSql).use { ps ->
                    blocked.forEachIndexed { i, chatId -> ps.setLong(i + 1, chatId) }
                    ps.executeQuery().use { rs ->
                        if (!rs.next()) {
                            null
                        } else {
                            QueuedItem(
                                id = rs.getLong("id"),
                                chatId = rs.getLong("chat_id"),
                                text = rs.getString("text"),
                                lat = (rs.getObject("lat") as? Number)?.toDouble(),
                                lon = (rs.getObject("lon") as? Number)?.toDouble(),
                                attempts = rs.getInt("attempts"),
                            )
                        }
                    }
                }
                if (item != null) {
                    conn.prepareStatement("DELETE FROM send_queue WHERE id = ?").use { del ->
                        del.setLong(1, item.id)
                        del.executeUpdate()
                    }
                }
                conn.commit()
                return item
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
    }

    // Remove and return all remaining rows for a specific chat (used when backing off a failing chat).
    fun drainChat(chatId: Long): List<QueuedItem> {
        val items = mutableListOf<QueuedItem>()
        ds.connection.use { conn ->
            conn.autoCommit = false
            try {
                conn.prepareStatement(
                    "SELECT id, chat_id, text, lat, lon, attempts FROM send_queue WHERE chat_id = ? ORDER BY id FOR UPDATE SKIP LOCKED",
                ).use { ps ->
                    ps.setLong(1, chatId)
                    ps.executeQuery().use { rs ->
                        while (rs.next()) {
                            items += QueuedItem(
                                id = rs.getLong("id"),
                                chatId = rs.getLong("chat_id"),
                                text = rs.getString("text"),
                                lat = (rs.getObject("lat") as? Number)?.toDouble(),
                                lon = (rs.getObject("lon") as? Number)?.toDouble(),
                                attempts = rs.getInt("attempts"),
                            )
                        }
                    }
                }
                if (items.isNotEmpty()) {
                    val ids = items.joinToString(",") { "?" }
                    conn.prepareStatement("DELETE FROM send_queue WHERE id IN ($ids)").use { del ->
                        items.forEachIndexed { i, item -> del.setLong(i + 1, item.id) }
                        del.executeUpdate()
                    }
                }
                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = true
            }
        }
        return items
    }

    fun count(): Int =
        ds.connection.use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT COUNT(*) FROM send_queue").use { rs ->
                    rs.next(); rs.getInt(1)
                }
            }
        }
}
