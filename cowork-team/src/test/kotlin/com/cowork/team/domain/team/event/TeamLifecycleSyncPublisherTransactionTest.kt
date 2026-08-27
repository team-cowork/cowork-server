package com.cowork.team.domain.team.event

import com.cowork.team.domain.team.repository.TeamEventStateRepository
import com.cowork.team.domain.teamMember.repository.TeamMemberEventStateRepository
import jakarta.persistence.LockModeType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.Lock
import org.springframework.transaction.annotation.Transactional

class TeamLifecycleSyncPublisherTransactionTest {

    @Test
    fun `acquires the startup lock outside a read-only transaction`() {
        val method = TeamLifecycleSyncPublisher::class.java.getDeclaredMethod("publishAllSnapshots")

        assertThat(method.getAnnotation(Transactional::class.java)).isNull()
    }

    @Test
    fun `team과 member snapshot은 tombstone ledger를 비관적 쓰기 잠금한다`() {
        val teamMethod = TeamEventStateRepository::class.java.getMethod(
            "findSnapshotBatch",
            Long::class.javaPrimitiveType,
            Pageable::class.java,
        )
        val memberMethod = TeamMemberEventStateRepository::class.java.getMethod(
            "findSnapshotBatch",
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            Pageable::class.java,
        )

        assertThat(teamMethod.getAnnotation(Lock::class.java).value).isEqualTo(LockModeType.PESSIMISTIC_WRITE)
        assertThat(memberMethod.getAnnotation(Lock::class.java).value).isEqualTo(LockModeType.PESSIMISTIC_WRITE)
    }
}
