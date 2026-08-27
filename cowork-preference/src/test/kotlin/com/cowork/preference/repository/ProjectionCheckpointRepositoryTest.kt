package com.cowork.preference.repository

import io.mockk.every
import io.mockk.mockk
import io.vertx.core.Future
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.PreparedQuery
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Transaction
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ProjectionCheckpointRepositoryTest {

    @Test
    fun `applies projection state then checkpoint before committing one database transaction`() = runBlocking {
        val fixture = TransactionFixture()
        val repository = ProjectionCheckpointRepository(fixture.pool)

        repository.inTransaction(CHECKPOINT) { connection ->
            assertEquals(fixture.connection, connection)
            fixture.calls += "projection"
        }

        assertEquals(
            listOf("begin", "outbox-order-lock", "projection", "checkpoint", "commit", "close"),
            fixture.calls,
        )
    }

    @Test
    fun `rolls back without checkpoint when projection storage fails`() {
        val fixture = TransactionFixture()
        val repository = ProjectionCheckpointRepository(fixture.pool)

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                repository.inTransaction(CHECKPOINT) {
                    fixture.calls += "projection"
                    error("database unavailable")
                }
            }
        }

        assertEquals(listOf("begin", "outbox-order-lock", "projection", "rollback", "close"), fixture.calls)
    }

    @Test
    fun `stores snapshot marker and next offset before one transaction commit`() = runBlocking {
        val fixture = TransactionFixture()
        val repository = ProjectionCheckpointRepository(fixture.pool)

        repository.completeSnapshot(CHECKPOINT, markerOffset = 11)

        assertEquals(listOf("begin", "snapshot-marker", "checkpoint", "commit", "close"), fixture.calls)
    }

    private class TransactionFixture {
        val calls = mutableListOf<String>()
        val pool = mockk<Pool>()
        val connection = mockk<SqlConnection>()
        private val transaction = mockk<Transaction>()
        private val orderLockQuery = mockk<PreparedQuery<RowSet<Row>>>()
        private val snapshotMarkerQuery = mockk<PreparedQuery<RowSet<Row>>>()
        private val checkpointQuery = mockk<PreparedQuery<RowSet<Row>>>()
        private val rows = mockk<RowSet<Row>> {
            every { rowCount() } returns 1
        }

        init {
            every { pool.connection } returns Future.succeededFuture(connection)
            every { connection.begin() } answers {
                calls += "begin"
                Future.succeededFuture(transaction)
            }
            every { connection.preparedQuery(match { it.contains("pg_advisory_xact_lock") }) } returns orderLockQuery
            every { connection.preparedQuery(match { it.contains("SET snapshot_completed_offset") }) } returns
                snapshotMarkerQuery
            every {
                connection.preparedQuery(match { it.contains("INSERT INTO tb_projection_consumer_checkpoints") })
            } returns
                checkpointQuery
            every { orderLockQuery.execute(any()) } answers {
                calls += "outbox-order-lock"
                Future.succeededFuture(rows)
            }
            every { checkpointQuery.execute(any()) } answers {
                calls += "checkpoint"
                Future.succeededFuture(rows)
            }
            every { snapshotMarkerQuery.execute(any()) } answers {
                calls += "snapshot-marker"
                Future.succeededFuture(rows)
            }
            every { transaction.commit() } answers {
                calls += "commit"
                Future.succeededFuture()
            }
            every { transaction.rollback() } answers {
                calls += "rollback"
                Future.succeededFuture()
            }
            every { connection.close() } answers {
                calls += "close"
                Future.succeededFuture()
            }
        }
    }

    private companion object {
        val CHECKPOINT = ProjectionCheckpoint(
            consumerGroup = "cowork-preference-team-member-projection",
            topic = "team.member.event",
            partition = 0,
            nextOffset = 12,
            topicId = "topic-id",
        )
    }
}
