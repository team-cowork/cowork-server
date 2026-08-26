package com.cowork.team.global.outbox

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.kafka.core.KafkaTemplate
import tools.jackson.databind.ObjectMapper
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import javax.sql.DataSource

class KafkaOutboxRelayTest {
    @Test
    fun `선두 outbox row를 locking read한 transaction이 실패하면 rollback 후 lock을 해제한다`() {
        val dataSource = mockk<DataSource>()
        val connection = mockk<Connection>(relaxed = true)
        val acquireStatement = mockk<PreparedStatement>(relaxed = true)
        val acquireResult = mockk<ResultSet>(relaxed = true)
        val releaseStatement = mockk<PreparedStatement>(relaxed = true)
        val releaseResult = mockk<ResultSet>(relaxed = true)
        every { dataSource.connection } returns connection
        every { connection.prepareStatement("SELECT GET_LOCK(?, 0)") } returns acquireStatement
        every { acquireStatement.executeQuery() } returns acquireResult
        every { acquireResult.next() } returns true
        every { acquireResult.getInt(1) } returns 1
        every { acquireResult.wasNull() } returns false
        every { connection.prepareStatement(match { it.startsWith("SELECT id, topic") }) } throws
            SQLException("temporary query failure")
        every { connection.prepareStatement("SELECT RELEASE_LOCK(?)") } returns releaseStatement
        every { releaseStatement.executeQuery() } returns releaseResult
        every { releaseResult.next() } returns true
        every { releaseResult.getInt(1) } returns 1
        every { releaseResult.wasNull() } returns false
        val relay = KafkaOutboxRelay(
            dataSource,
            mockk<KafkaTemplate<String, Any>>(),
            mockk<ObjectMapper>(),
        )

        relay.relayPendingEvents()

        verify(exactly = 1) { connection.autoCommit = false }
        verify(exactly = 1) {
            connection.prepareStatement(match { it.endsWith("ORDER BY id ASC LIMIT 100 FOR UPDATE") })
        }
        verify(exactly = 1) { connection.rollback() }
        verify(exactly = 1) { connection.prepareStatement("SELECT RELEASE_LOCK(?)") }
        verify(exactly = 1) { connection.close() }
    }
}
