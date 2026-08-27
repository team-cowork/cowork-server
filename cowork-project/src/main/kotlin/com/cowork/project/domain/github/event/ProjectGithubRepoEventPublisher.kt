package com.cowork.project.domain.github.event

import com.cowork.project.domain.github.entity.ProjectGithubRepo
import com.cowork.project.domain.github.repository.ProjectGithubRepoEventTombstoneRepository
import com.cowork.project.domain.github.service.GithubRepoUrlParser
import com.cowork.project.global.outbox.OutboxWriter
import com.cowork.project.global.projection.toProjectionPrecision
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale

const val PROJECT_GITHUB_REPO_EVENT_TOPIC = "project.github-repo.event"

@Component
class ProjectGithubRepoEventPublisher(
    private val entityManager: EntityManager,
    private val outboxWriter: OutboxWriter,
    private val tombstoneRepository: ProjectGithubRepoEventTombstoneRepository,
) {
    fun publishUpsert(repoLink: ProjectGithubRepo, occurredAt: Instant = Instant.now()) =
        publishActive(repoLink, occurredAt)

    fun publishDelete(repoLink: ProjectGithubRepo, occurredAt: Instant = Instant.now()) {
        val existing = tombstoneRepository.findByRepoIdForUpdate(repoLink.id)
        val currentVersion = maxOf(repoLink.stateOccurredAt, existing?.stateOccurredAt ?: Instant.EPOCH)
        val eventVersion = nextVersion(currentVersion, occurredAt)
        repoLink.stateOccurredAt = eventVersion
        val tombstone = existing?.also { it.replaceFrom(repoLink, eventVersion) }
            ?: tombstoneRepository.save(ProjectGithubRepoEventTombstone.from(repoLink, eventVersion))
        publishTombstone(tombstone, snapshot = false)
    }

    fun publishSnapshot(repoLink: ProjectGithubRepo) = publish(
        ProjectGithubRepoEventType.UPSERT,
        repoLink,
        repoLink.stateOccurredAt,
        snapshot = true,
    )

    fun publishSnapshot(tombstone: ProjectGithubRepoEventTombstone) = publishTombstone(tombstone, snapshot = true)

    private fun publishActive(repoLink: ProjectGithubRepo, occurredAt: Instant) {
        val tombstone = tombstoneRepository.findByRepoIdForUpdate(repoLink.id)
        val currentVersion = maxOf(repoLink.stateOccurredAt, tombstone?.stateOccurredAt ?: Instant.EPOCH)
        val eventVersion = nextVersion(currentVersion, occurredAt)
        repoLink.stateOccurredAt = eventVersion
        tombstone?.let(tombstoneRepository::delete)
        publish(ProjectGithubRepoEventType.UPSERT, repoLink, eventVersion, snapshot = false)
    }

    private fun publish(
        eventType: ProjectGithubRepoEventType,
        repoLink: ProjectGithubRepo,
        occurredAt: Instant,
        snapshot: Boolean,
    ) {
        val ref = checkNotNull(GithubRepoUrlParser.parse(repoLink.githubRepoUrl)) {
            "Canonical GitHub repository URL is invalid: ${repoLink.githubRepoUrl}"
        }
        val event = ProjectGithubRepoEvent(
            eventType = eventType,
            repoId = repoLink.id,
            projectId = repoLink.projectId,
            teamId = repoLink.teamId,
            githubRepoUrl = repoLink.githubRepoUrl,
            owner = ref.owner.lowercase(Locale.ROOT),
            repo = ref.repo.lowercase(Locale.ROOT),
            webhookChannelId = repoLink.githubWebhookChannelId,
            occurredAt = occurredAt.toProjectionPrecision(),
            snapshot = snapshot,
        )
        entityManager.flush()
        outboxWriter.enqueue(PROJECT_GITHUB_REPO_EVENT_TOPIC, repoLink.id.toString(), event)
    }

    private fun publishTombstone(tombstone: ProjectGithubRepoEventTombstone, snapshot: Boolean) {
        val event = ProjectGithubRepoEvent(
            eventType = ProjectGithubRepoEventType.DELETE,
            repoId = tombstone.repoId,
            projectId = tombstone.projectId,
            teamId = tombstone.teamId,
            githubRepoUrl = tombstone.githubRepoUrl,
            owner = tombstone.owner,
            repo = tombstone.repo,
            webhookChannelId = tombstone.githubWebhookChannelId,
            occurredAt = tombstone.stateOccurredAt,
            snapshot = snapshot,
        )
        entityManager.flush()
        outboxWriter.enqueue(PROJECT_GITHUB_REPO_EVENT_TOPIC, tombstone.repoId.toString(), event)
    }

    private fun nextVersion(current: Instant, requested: Instant): Instant = maxOf(
        requested.toProjectionPrecision(),
        current.toProjectionPrecision().plus(1, ChronoUnit.MICROS),
    )
}
