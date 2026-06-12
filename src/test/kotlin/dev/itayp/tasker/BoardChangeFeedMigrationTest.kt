package dev.itayp.tasker

import liquibase.Contexts
import liquibase.LabelExpression
import liquibase.Liquibase
import liquibase.database.DatabaseFactory
import liquibase.database.jvm.JdbcConnection
import liquibase.resource.ClassLoaderResourceAccessor
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Proves changeset 002 (board-keying the change feed) applies cleanly to a database that already
 * holds pre-002 rows — the live-prod path. The Phase-2 plan made a deliberate one-time drop of the
 * existing change events (their snapshots are under the now-orphaned user DEK), so the contract is:
 * old rows are removed, and the tables come out keyed by board.
 *
 * Liquibase is driven in two stages on one container — apply 001 only, seed user-keyed rows, then
 * apply the rest — because once the full changelog has run the pre-002 shape no longer exists.
 */
class BoardChangeFeedMigrationTest {

    @Test
    fun `changeset 002 drops pre-existing rows and re-keys the change feed to the board`() {
        val container = PostgreSQLContainer(PostgreSQLContainer.IMAGE)
            .withDatabaseName("taskboard")
            .withUsername("taskboard_test")
            .withPassword("Password")
        container.start()
        try {
            DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { conn ->
                val database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(JdbcConnection(conn))
                Liquibase("db/changelog/db.changelog-master.xml", ClassLoaderResourceAccessor(), database).use { lb ->
                    // Stage 1: apply only changeset 001 (the consolidated baseline is a single changeset).
                    lb.update(1, Contexts(), LabelExpression())

                    val userId = UUID.randomUUID()
                    val boardId = UUID.randomUUID()
                    val taskId = UUID.randomUUID()
                    seedPre002Rows(conn, userId, boardId, taskId)

                    // Sanity: the pre-002 row really exists before the migration drops it.
                    assertEquals(1, count(conn, "backlog_task_change_event"))
                    assertEquals(1, count(conn, "backlog_task_watermark"))

                    // Stage 2: apply the remaining changesets (002).
                    lb.update(Contexts(), LabelExpression())

                    // The one-time drop: pre-002 change events are gone.
                    assertEquals(0, count(conn, "backlog_task_change_event"))
                    // The watermark table was dropped and recreated, so its pre-002 row is gone too.
                    assertEquals(0, count(conn, "backlog_task_watermark"))

                    // Change events are now board-keyed with a nullable actor; user_id is gone.
                    assertTrue(columnExists(conn, "backlog_task_change_event", "board_id"))
                    assertFalse(nullable(conn, "backlog_task_change_event", "board_id"))
                    assertTrue(columnExists(conn, "backlog_task_change_event", "actor_user_id"))
                    assertTrue(nullable(conn, "backlog_task_change_event", "actor_user_id"))
                    assertFalse(columnExists(conn, "backlog_task_change_event", "user_id"))

                    // Watermark is keyed by board now.
                    assertTrue(columnExists(conn, "backlog_task_watermark", "board_id"))
                    assertFalse(columnExists(conn, "backlog_task_watermark", "user_id"))

                    // The new shape is usable: a board-keyed event (null actor) and watermark insert.
                    insertBoardKeyedRows(conn, boardId, taskId)
                    assertEquals(1, count(conn, "backlog_task_change_event"))
                    assertEquals(1, count(conn, "backlog_task_watermark"))
                }
            }
        } finally {
            container.stop()
        }
    }

    private fun seedPre002Rows(conn: Connection, userId: UUID, boardId: UUID, taskId: UUID) {
        val now = Timestamp.from(Instant.now())
        conn.prepareStatement("INSERT INTO users (id, created_at) VALUES (?, ?)").use {
            it.setObject(1, userId); it.setTimestamp(2, now); it.executeUpdate()
        }
        conn.prepareStatement("INSERT INTO board (id, created_at) VALUES (?, ?)").use {
            it.setObject(1, boardId); it.setTimestamp(2, now); it.executeUpdate()
        }
        conn.prepareStatement(
            "INSERT INTO backlog_task_change_event (id, user_id, task_id, change_type, occurred_at) " +
                "VALUES (?, ?, ?, 'CREATED', ?)",
        ).use {
            it.setObject(1, UUID.randomUUID()); it.setObject(2, userId); it.setObject(3, taskId)
            it.setTimestamp(4, now); it.executeUpdate()
        }
        conn.prepareStatement(
            "INSERT INTO backlog_task_watermark (user_id, tasks_changed_at) VALUES (?, ?)",
        ).use {
            it.setObject(1, userId); it.setTimestamp(2, now); it.executeUpdate()
        }
    }

    private fun insertBoardKeyedRows(conn: Connection, boardId: UUID, taskId: UUID) {
        val now = Timestamp.from(Instant.now())
        conn.prepareStatement(
            "INSERT INTO backlog_task_change_event (id, board_id, actor_user_id, task_id, change_type, occurred_at) " +
                "VALUES (?, ?, NULL, ?, 'CREATED', ?)",
        ).use {
            it.setObject(1, UUID.randomUUID()); it.setObject(2, boardId); it.setObject(3, taskId)
            it.setTimestamp(4, now); it.executeUpdate()
        }
        conn.prepareStatement(
            "INSERT INTO backlog_task_watermark (board_id, tasks_changed_at) VALUES (?, ?)",
        ).use {
            it.setObject(1, boardId); it.setTimestamp(2, now); it.executeUpdate()
        }
    }

    private fun count(conn: Connection, table: String): Int =
        conn.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM $table").use { rs -> rs.next(); rs.getInt(1) }
        }

    private fun columnExists(conn: Connection, table: String, column: String): Boolean =
        conn.prepareStatement(
            "SELECT 1 FROM information_schema.columns WHERE table_name = ? AND column_name = ?",
        ).use {
            it.setString(1, table); it.setString(2, column)
            it.executeQuery().use { rs -> rs.next() }
        }

    private fun nullable(conn: Connection, table: String, column: String): Boolean =
        conn.prepareStatement(
            "SELECT is_nullable FROM information_schema.columns WHERE table_name = ? AND column_name = ?",
        ).use {
            it.setString(1, table); it.setString(2, column)
            it.executeQuery().use { rs ->
                check(rs.next()) { "column $table.$column not found" }
                rs.getString(1) == "YES"
            }
        }
}
