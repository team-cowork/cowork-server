package com.cowork.team.domain.team.event

import com.cowork.team.domain.team.repository.TeamRepository
import com.cowork.team.domain.teamMember.entity.TeamMember
import com.cowork.team.domain.teamMember.repository.TeamMemberRepository
import com.cowork.team.domain.teamRole.entity.TeamRole
import com.cowork.team.global.projection.ProjectionSnapshotCompletionPublisher
import com.cowork.team.global.projection.toProjectionSourceInstant
import net.javacrumbs.shedlock.core.LockConfiguration
import net.javacrumbs.shedlock.core.LockingTaskExecutor
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant

@Component
class TeamLifecycleSyncPublisher(
    private val teamRepository: TeamRepository,
    private val teamMemberRepository: TeamMemberRepository,
    private val teamEventPublisher: TeamEventPublisher,
    private val teamMemberEventPublisher: TeamMemberEventPublisher,
    private val completionPublisher: ProjectionSnapshotCompletionPublisher,
    private val lockingTaskExecutor: LockingTaskExecutor,
    transactionManager: PlatformTransactionManager,
) {
    private val transaction = TransactionTemplate(transactionManager)

    fun publishTeamSnapshot(
        actorUserId: Long,
        members: List<TeamMember>,
        occurredAt: Instant = Instant.now(),
        snapshot: Boolean = false,
    ) {
        if (members.isEmpty()) return

        val team = members.first().team
        teamEventPublisher.publishMemberInvited(
            teamId = team.id,
            teamName = team.name,
            actorUserId = actorUserId,
            targetUserIds = members.map(TeamMember::userId),
            occurredAt = occurredAt,
        )

        members.groupBy(TeamMember::role)
            .filterKeys { it != TeamRole.MEMBER }
            .forEach { (role, groupedMembers) ->
                teamEventPublisher.publishRoleChanged(
                    teamId = team.id,
                    teamName = team.name,
                    actorUserId = actorUserId,
                    targetUserIds = groupedMembers.map(TeamMember::userId),
                    newRole = role.name,
                    occurredAt = occurredAt,
                )
            }

        members.forEach { teamMemberEventPublisher.publishUpsert(it, occurredAt, snapshot) }
    }

    internal fun publishPeriodicSnapshot(members: List<TeamMember>) {
        if (members.isEmpty()) return
        val team = members.first().team
        val actorUserId = members.firstOrNull { it.role == TeamRole.OWNER }?.userId ?: members.first().userId
        val teamSourceTime = requireNotNull(team.updatedAt ?: team.createdAt) {
            "팀 snapshot source timestamp가 없습니다: ${team.id}"
        }.toProjectionSourceInstant()
        teamEventPublisher.publishTeamSnapshot(
            teamId = team.id,
            teamName = team.name,
            actorUserId = actorUserId,
            targetUserIds = members.map(TeamMember::userId),
            occurredAt = teamSourceTime,
        )
        members.forEach(teamMemberEventPublisher::publishSnapshot)
    }

    @EventListener(ApplicationReadyEvent::class)
    fun publishAllSnapshots() {
        val lockConfig = LockConfiguration(
            Instant.now(),
            "publishAllTeamSnapshots",
            Duration.ofMinutes(10),
            Duration.ZERO,
        )
        lockingTaskExecutor.executeWithLock(Runnable { publishAllInTransactions() }, lockConfig)
    }

    @Scheduled(initialDelay = 30_000, fixedDelay = 300_000)
    @SchedulerLock(name = "republishAllTeamSnapshots", lockAtMostFor = "PT10M")
    fun republishAllSnapshots() {
        publishAllInTransactions()
    }

    private fun publishAllInTransactions() {
        var afterId = 0L
        while (true) {
            val nextAfterId = transaction.execute {
                val teams = teamRepository.findSnapshotBatch(afterId, PageRequest.of(0, PAGE_SIZE))
                teams.forEach { team ->
                    publishPeriodicSnapshot(teamMemberRepository.findSnapshotByTeamId(team.id))
                }
                teams.lastOrNull()?.id
            } ?: break
            afterId = nextAfterId
        }
        completionPublisher.publishCompleted(setOf(Topics.TEAM_LIFECYCLE, Topics.TEAM_MEMBER_EVENT))
    }

    private companion object {
        const val PAGE_SIZE = 500
    }
}
