package com.cowork.project.domain.calendar.event

import com.cowork.project.domain.calendar.repository.ProjectCalendarCategoryRepository
import com.cowork.project.domain.calendar.repository.ProjectCalendarEventRepository
import com.cowork.project.domain.calendar.repository.ProjectReadableUserStateRepository
import com.cowork.project.domain.project.repository.ProjectRepository
import com.cowork.project.global.projection.ProjectionReadinessState
import com.cowork.project.global.projection.ProjectionSnapshotCompletionPublisher
import com.cowork.project.global.projection.ProjectionSnapshotLock
import com.cowork.project.global.projection.ProjectionStreams
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 프로젝트 일정 상태와 읽기 가능 사용자 상태를 시작할 때와 주기적으로 모두 다시 발행하고 완료 마커를 남긴다.
 * 일정 상태는 이 서비스의 원본만으로 만들고, 읽기 가능 사용자는 접근 권한과 팀 멤버 projection을 따라잡은 뒤에 만든다.
 */
@Component
class ProjectCalendarSnapshotPublisher(
    private val projectCalendarEventRepository: ProjectCalendarEventRepository,
    private val projectCalendarCategoryRepository: ProjectCalendarCategoryRepository,
    private val projectCalendarEventPublisher: ProjectCalendarEventPublisher,
    private val projectRepository: ProjectRepository,
    private val projectReadableUserStateRepository: ProjectReadableUserStateRepository,
    private val projectReadableUserStatePublisher: ProjectReadableUserStatePublisher,
    private val projectionSnapshotCompletionPublisher: ProjectionSnapshotCompletionPublisher,
    private val projectionReadinessState: ProjectionReadinessState,
    private val projectionSnapshotLock: ProjectionSnapshotLock,
    projectionStreams: ProjectionStreams,
    transactionManager: PlatformTransactionManager,
) {
    private val transaction = TransactionTemplate(transactionManager)
    private val readableUserUpstream = setOf(projectionStreams.projectAccess, projectionStreams.teamMember)
    private val eventsPublished = AtomicBoolean(false)
    private val readableUsersPublished = AtomicBoolean(false)
    private val publishing = AtomicBoolean(false)

    @Scheduled(initialDelay = INITIAL_RETRY_DELAY_MS, fixedDelay = INITIAL_RETRY_DELAY_MS)
    fun publishInitialSnapshotsWhenReady() {
        if (eventsPublished.get() && readableUsersPublished.get()) return
        publishIfReady(initial = true)
    }

    @Scheduled(initialDelay = REPUBLISH_INTERVAL_MS, fixedDelay = REPUBLISH_INTERVAL_MS)
    fun republishAllSnapshots() {
        if (!eventsPublished.get() || !readableUsersPublished.get()) return
        publishIfReady(initial = false)
    }

    private fun publishIfReady(initial: Boolean) {
        if (!publishing.compareAndSet(false, true)) return
        try {
            projectionSnapshotLock.tryRun(EVENT_LOCK_NAME) {
                if (initial && eventsPublished.get()) return@tryRun
                publishEvents()
                projectionSnapshotCompletionPublisher.publishCompleted(setOf(PROJECT_CALENDAR_EVENT_STATE_TOPIC))
                eventsPublished.set(true)
            }
            projectionSnapshotLock.tryRun(READABLE_USER_LOCK_NAME) {
                if (initial && readableUsersPublished.get()) return@tryRun
                if (!projectionReadinessState.areCurrent(readableUserUpstream)) return@tryRun
                publishReadableUsers()
                if (!projectionReadinessState.areCurrent(readableUserUpstream)) return@tryRun
                projectionSnapshotCompletionPublisher.publishCompleted(setOf(PROJECT_READABLE_USER_STATE_TOPIC))
                readableUsersPublished.set(true)
            }
        } finally {
            publishing.set(false)
        }
    }

    private fun publishEvents() {
        var afterId = 0L
        while (true) {
            afterId = transaction.execute {
                val events = projectCalendarEventRepository.findSnapshotBatch(afterId, PageRequest.of(0, PAGE_SIZE))
                val categories = projectCalendarCategoryRepository
                    .findAllById(events.mapNotNull { it.categoryId }.toSet())
                    .associateBy { it.id }
                events.forEach { projectCalendarEventPublisher.publishSnapshot(it, categories[it.categoryId]) }
                events.lastOrNull()?.id
            } ?: break
        }
    }

    private fun publishReadableUsers() {
        var afterId = 0L
        while (true) {
            afterId = transaction.execute {
                val projects = projectRepository.findSnapshotBatch(afterId, PageRequest.of(0, PAGE_SIZE))
                projects.forEach { projectReadableUserStatePublisher.publishSnapshot(it.id) }
                projects.lastOrNull()?.id
            } ?: break
        }
        var afterDeletedId = 0L
        while (true) {
            afterDeletedId = transaction.execute {
                val states = projectReadableUserStateRepository
                    .findDeletedSnapshotBatch(afterDeletedId, PageRequest.of(0, PAGE_SIZE))
                states.forEach(projectReadableUserStatePublisher::publishSnapshot)
                states.lastOrNull()?.projectId
            } ?: break
        }
    }

    private companion object {
        const val PAGE_SIZE = 500
        const val INITIAL_RETRY_DELAY_MS = 1_000L
        const val REPUBLISH_INTERVAL_MS = 300_000L
        const val EVENT_LOCK_NAME = "cowork-project:calendar-event-snapshot"
        const val READABLE_USER_LOCK_NAME = "cowork-project:readable-user-snapshot"
    }
}
