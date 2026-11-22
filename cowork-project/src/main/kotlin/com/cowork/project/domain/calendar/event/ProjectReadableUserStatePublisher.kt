package com.cowork.project.domain.calendar.event

import com.cowork.project.domain.calendar.entity.ProjectReadableUserState
import com.cowork.project.domain.calendar.repository.ProjectReadableUserStateRepository
import com.cowork.project.domain.project.event.nextMonotonicStateVersion
import com.cowork.project.domain.project.repository.ProjectRepository
import com.cowork.project.global.outbox.OutboxWriter
import com.cowork.project.global.projection.ProjectionReadinessState
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * 프로젝트별 읽기 가능 사용자 목록을 계산해 바뀌었을 때만 발행한다.
 * 목록은 preference 접근 권한 projection과 팀 멤버 projection으로 정해지므로 두 projection을 반영한 트랜잭션에서 부른다.
 * projection을 따라잡는 중에는 중간 상태를 내보내지 않고, 준비된 뒤 snapshot이 전체를 다시 계산한다.
 */
@Component
class ProjectReadableUserStatePublisher(
    private val projectRepository: ProjectRepository,
    private val projectReadableUserStateRepository: ProjectReadableUserStateRepository,
    private val outboxWriter: OutboxWriter,
    private val projectionReadinessState: ProjectionReadinessState,
) {
    fun refreshIfReady(projectIds: Collection<Long>) {
        if (!projectionReadinessState.isReady()) return
        projectIds.distinct().sorted().forEach { refresh(it, snapshot = false) }
    }

    fun refreshTeamIfReady(teamId: Long) {
        if (!projectionReadinessState.isReady()) return
        projectRepository.findAllByTeamIdOrderByIdAsc(teamId).forEach { refresh(it.id, snapshot = false) }
    }

    /** snapshot은 바뀐 것이 없어도 현재 상태를 다시 보낸다. 호출자가 upstream 준비를 확인한다. */
    fun publishSnapshot(projectId: Long) = refresh(projectId, snapshot = true)

    fun publishSnapshot(state: ProjectReadableUserState) = enqueue(state, snapshot = true)

    fun publishDeleted(projectId: Long, teamId: Long, occurredAt: Instant = Instant.now()) {
        val state = projectReadableUserStateRepository.findForUpdate(projectId)
        if (state?.deleted == true) return
        val version = nextMonotonicStateVersion(state?.stateOccurredAt ?: Instant.EPOCH, occurredAt)
        val saved = state?.apply {
            userIds = ""
            deleted = true
            stateOccurredAt = version
        } ?: ProjectReadableUserState(projectId, teamId, "", deleted = true, stateOccurredAt = version)
        enqueue(projectReadableUserStateRepository.save(saved), snapshot = false)
    }

    private fun refresh(projectId: Long, snapshot: Boolean) {
        val project = projectRepository.findById(projectId).orElse(null) ?: return
        val userIds = ProjectReadableUserState.encode(projectRepository.findReadableUserIds(projectId))
        val state = projectReadableUserStateRepository.findForUpdate(projectId)
        if (state != null && !state.deleted && state.userIds == userIds) {
            if (snapshot) enqueue(state, snapshot = true)
            return
        }
        val version = nextMonotonicStateVersion(state?.stateOccurredAt ?: Instant.EPOCH, Instant.now())
        val saved = state?.apply {
            this.userIds = userIds
            deleted = false
            stateOccurredAt = version
        } ?: ProjectReadableUserState(project.id, project.teamId, userIds, stateOccurredAt = version)
        enqueue(projectReadableUserStateRepository.save(saved), snapshot)
    }

    private fun enqueue(state: ProjectReadableUserState, snapshot: Boolean) {
        val payload = ProjectReadableUserStatePayload(
            eventType = if (state.deleted) CalendarStateEventType.DELETE else CalendarStateEventType.UPSERT,
            projectId = state.projectId,
            teamId = state.teamId,
            userIds = state.userIdList,
            occurredAt = state.stateOccurredAt,
            snapshot = snapshot,
        )
        outboxWriter.enqueue(PROJECT_READABLE_USER_STATE_TOPIC, state.projectId.toString(), payload)
    }
}
