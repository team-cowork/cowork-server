package com.cowork.team.domain.team.event

import com.cowork.team.domain.team.entity.Team
import com.cowork.team.domain.team.repository.TeamRepository
import com.cowork.team.domain.teamMember.entity.TeamMember
import com.cowork.team.domain.teamMember.repository.TeamMemberRepository
import com.cowork.team.domain.teamRole.entity.TeamRole
import com.cowork.team.global.projection.ProjectionSnapshotCompletionPublisher
import io.mockk.mockk
import io.mockk.verify
import jakarta.persistence.LockModeType
import net.javacrumbs.shedlock.core.LockingTaskExecutor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.Lock
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

class TeamLifecycleSyncPublisherTransactionTest {

    @Test
    fun `acquires the startup lock outside a read-only transaction`() {
        val method = TeamLifecycleSyncPublisher::class.java.getDeclaredMethod("publishAllSnapshots")

        assertThat(method.getAnnotation(Transactional::class.java)).isNull()
    }

    @Test
    fun `periodic snapshot은 action lifecycle을 재발행하지 않고 source version 상태만 발행한다`() {
        val teamEventPublisher = mockk<TeamEventPublisher>(relaxed = true)
        val teamMemberEventPublisher = mockk<TeamMemberEventPublisher>(relaxed = true)
        val publisher = TeamLifecycleSyncPublisher(
            mockk<TeamRepository>(),
            mockk<TeamMemberRepository>(),
            teamEventPublisher,
            teamMemberEventPublisher,
            mockk<ProjectionSnapshotCompletionPublisher>(relaxed = true),
            mockk<LockingTaskExecutor>(),
            mockk<PlatformTransactionManager>(),
        )
        val sourceTime = LocalDateTime.of(2026, 8, 26, 12, 0, 0, 123_456_789)
        val team = Team(id = 7L, name = "Backend", description = null, iconUrl = null, ownerId = 11L).apply {
            createdAt = sourceTime.minusSeconds(1)
            updatedAt = sourceTime
        }
        val owner = TeamMember(team = team, userId = 11L, role = TeamRole.OWNER).apply {
            joinedAt = sourceTime.minusSeconds(1)
            updatedAt = sourceTime.minusSeconds(1)
        }

        publisher.publishPeriodicSnapshot(listOf(owner))

        verify(exactly = 1) {
            teamEventPublisher.publishTeamSnapshot(
                7L,
                "Backend",
                11L,
                listOf(11L),
                sourceTime.atZone(ZoneId.systemDefault()).toInstant().truncatedTo(ChronoUnit.MICROS),
            )
        }
        verify(exactly = 1) { teamMemberEventPublisher.publishSnapshot(owner) }
        verify(exactly = 0) { teamEventPublisher.publishMemberInvited(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { teamEventPublisher.publishRoleChanged(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `team과 member snapshot은 source row를 비관적 쓰기 잠금한다`() {
        val teamMethod = TeamRepository::class.java.getMethod(
            "findSnapshotBatch",
            Long::class.javaPrimitiveType,
            Pageable::class.java,
        )
        val memberMethod = TeamMemberRepository::class.java.getMethod(
            "findSnapshotByTeamId",
            Long::class.javaPrimitiveType,
        )

        assertThat(teamMethod.getAnnotation(Lock::class.java).value).isEqualTo(LockModeType.PESSIMISTIC_WRITE)
        assertThat(memberMethod.getAnnotation(Lock::class.java).value).isEqualTo(LockModeType.PESSIMISTIC_WRITE)
    }
}
