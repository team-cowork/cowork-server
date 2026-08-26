package com.cowork.project.domain.project.event

import com.cowork.project.domain.project.repository.ProjectRepository
import com.cowork.project.domain.projectMember.event.ProjectMemberEventPublisher
import com.cowork.project.domain.projectMember.repository.ProjectMemberRepository
import com.cowork.project.global.projection.ProjectionSnapshotCompletionPublisher
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@Component
class ProjectProjectionSnapshotPublisher(
    private val projectRepository: ProjectRepository,
    private val projectMemberRepository: ProjectMemberRepository,
    private val projectEventPublisher: ProjectEventPublisher,
    private val projectMemberEventPublisher: ProjectMemberEventPublisher,
    private val completionPublisher: ProjectionSnapshotCompletionPublisher,
    transactionManager: PlatformTransactionManager,
) {
    private val transaction = TransactionTemplate(transactionManager)

    @EventListener(ApplicationReadyEvent::class)
    fun publishAllSnapshots() {
        publishAllInTransactions()
    }

    @Scheduled(initialDelay = 60_000, fixedDelay = 300_000)
    fun republishAllSnapshots() {
        publishAllSnapshots()
    }

    private fun publishAllInTransactions() {
        var afterId = 0L
        while (true) {
            val nextAfterId = transaction.execute {
                val projects = projectRepository.findSnapshotBatch(afterId, PageRequest.of(0, PAGE_SIZE))
                projects.forEach { project ->
                    projectEventPublisher.publishSnapshot(project)
                    projectMemberRepository.findSnapshotByProjectId(project.id)
                        .forEach(projectMemberEventPublisher::publishSnapshot)
                }
                projects.lastOrNull()?.id
            } ?: break
            afterId = nextAfterId
        }
        completionPublisher.publishCompleted(SNAPSHOT_TOPICS)
    }

    private companion object {
        const val PAGE_SIZE = 500
        val SNAPSHOT_TOPICS = setOf("project.event", "project.member.event")
    }
}
