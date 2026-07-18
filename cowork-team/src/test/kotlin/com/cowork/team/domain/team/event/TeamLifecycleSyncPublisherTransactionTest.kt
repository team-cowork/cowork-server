package com.cowork.team.domain.team.event

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.transaction.annotation.Transactional

class TeamLifecycleSyncPublisherTransactionTest {

    @Test
    fun `acquires the startup lock outside a read-only transaction`() {
        val method = TeamLifecycleSyncPublisher::class.java.getDeclaredMethod("publishAllSnapshots")

        assertThat(method.getAnnotation(Transactional::class.java)).isNull()
    }
}
