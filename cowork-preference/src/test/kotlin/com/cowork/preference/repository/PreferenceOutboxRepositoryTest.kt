package com.cowork.preference.repository

import com.cowork.preference.messaging.PreferenceEvents
import io.mockk.every
import io.mockk.mockk
import io.vertx.core.Future
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.PreparedQuery
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowIterator
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlConnection
import io.vertx.sqlclient.Transaction
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.OffsetDateTime

class PreferenceOutboxRepositoryTest {

    @Test
    fun `acquires producer advisory lock before mutation and outbox insert`() = runBlocking {
        val fixture = EnqueueFixture()
        val repository = PreferenceOutboxRepository(fixture.pool)

        repository.inTransaction { connection ->
            fixture.calls += "mutation"
            repository.enqueue(connection, EVENT)
        }

        assertEquals(
            listOf("begin", "outbox-order-lock", "mutation", "enqueue", "commit", "close"),
            fixture.calls,
        )
    }

    @Test
    fun `publishes the oldest record while holding the cross replica advisory lock`() = runBlocking {
        val fixture = DispatchFixture(lockAcquired = true)
        val repository = PreferenceOutboxRepository(fixture.pool)

        val result = repository.dispatchNextIfLeader { event ->
            fixture.calls += "publish:${event.key}"
        }

        assertEquals(PreferenceOutboxDispatchResult.PUBLISHED, result)
        assertEquals(
            listOf("begin", "lock", "load-oldest", "publish:11:22", "mark-published", "commit", "close"),
            fixture.calls,
        )
    }

    @Test
    fun `does not overtake the oldest record when Kafka publication fails`() {
        val fixture = DispatchFixture(lockAcquired = true)
        val repository = PreferenceOutboxRepository(fixture.pool)

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                repository.dispatchNextIfLeader {
                    fixture.calls += "publish"
                    error("Kafka unavailable")
                }
            }
        }

        assertEquals(listOf("begin", "lock", "load-oldest", "publish", "rollback", "close"), fixture.calls)
    }

    @Test
    fun `non leader dispatcher cannot select or publish a record`() = runBlocking {
        val fixture = DispatchFixture(lockAcquired = false)
        val repository = PreferenceOutboxRepository(fixture.pool)

        val result = repository.dispatchNextIfLeader { fixture.calls += "publish" }

        assertEquals(PreferenceOutboxDispatchResult.BUSY, result)
        assertEquals(listOf("begin", "lock", "commit", "close"), fixture.calls)
    }

    private class EnqueueFixture {
        val calls = mutableListOf<String>()
        val pool = mockk<Pool>()
        private val connection = mockk<SqlConnection>()
        private val transaction = mockk<Transaction>()
        private val orderLockQuery = mockk<PreparedQuery<RowSet<Row>>>()
        private val enqueueQuery = mockk<PreparedQuery<RowSet<Row>>>()
        private val rows = mockk<RowSet<Row>>()

        init {
            every { pool.connection } returns Future.succeededFuture(connection)
            every { connection.begin() } answers {
                calls += "begin"
                Future.succeededFuture(transaction)
            }
            every { connection.preparedQuery(match { it.contains("INSERT INTO tb_preference_event_outbox") }) } returns
                enqueueQuery
            every { connection.preparedQuery(match { it.contains("pg_advisory_xact_lock") }) } returns orderLockQuery
            every { orderLockQuery.execute(any()) } answers {
                calls += "outbox-order-lock"
                Future.succeededFuture(rows)
            }
            every { enqueueQuery.execute(any()) } answers {
                calls += "enqueue"
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

    private class DispatchFixture(lockAcquired: Boolean) {
        val calls = mutableListOf<String>()
        val pool = mockk<Pool>()
        private val connection = mockk<SqlConnection>()
        private val transaction = mockk<Transaction>()
        private val lockQuery = mockk<PreparedQuery<RowSet<Row>>>()
        private val loadQuery = mockk<PreparedQuery<RowSet<Row>>>()
        private val updateQuery = mockk<PreparedQuery<RowSet<Row>>>()
        private val lockRows = rowSet(
            mockk<Row> {
                every { getBoolean("acquired") } returns lockAcquired
            },
        )
        private val eventRows = rowSet(
            mockk<Row> {
                every { getLong("id") } returns 5L
                every { getString("topic") } returns EVENT.topic
                every { getInteger("partition_id") } returns null
                every { getString("record_key") } returns EVENT.key
                every { getString("payload") } returns EVENT.payload.encode()
                every { getOffsetDateTime("occurred_at") } returns OffsetDateTime.parse("2026-08-26T01:02:03Z")
            },
        )
        private val updatedRows = mockk<RowSet<Row>> {
            every { rowCount() } returns 1
        }

        init {
            every { pool.connection } returns Future.succeededFuture(connection)
            every { connection.begin() } answers {
                calls += "begin"
                Future.succeededFuture(transaction)
            }
            every { connection.preparedQuery(match { it.contains("pg_try_advisory_xact_lock") }) } returns lockQuery
            every { connection.preparedQuery(match { it.contains("ORDER BY id") }) } returns loadQuery
            every { connection.preparedQuery(match { it.contains("SET published_at") }) } returns updateQuery
            every { lockQuery.execute(any()) } answers {
                calls += "lock"
                Future.succeededFuture(lockRows)
            }
            every { loadQuery.execute() } answers {
                calls += "load-oldest"
                Future.succeededFuture(eventRows)
            }
            every { updateQuery.execute(any()) } answers {
                calls += "mark-published"
                Future.succeededFuture(updatedRows)
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
        val EVENT = PreferenceEvents.channelNotificationChanged(
            accountId = 11,
            channelId = 22,
            notification = false,
            occurredAt = Instant.parse("2026-08-26T01:02:03Z"),
        )

        fun rowSet(row: Row): RowSet<Row> = mockk {
            every { iterator() } answers {
                mockk<RowIterator<Row>> {
                    every { hasNext() } returnsMany listOf(true, false)
                    every { next() } returns row
                }
            }
        }
    }
}
