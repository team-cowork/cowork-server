package com.cowork.project.global.consumer

import com.cowork.project.domain.channel.entity.ChannelProjection
import com.cowork.project.domain.channel.repository.ChannelProjectionRepository
import com.cowork.project.domain.github.event.ProjectGithubRepoEventPublisher
import com.cowork.project.domain.github.repository.ProjectGithubRepoRepository
import com.cowork.project.global.projection.toProjectionPrecision
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Component
class ChannelProjectionHandler(
    private val channelProjectionRepository: ChannelProjectionRepository,
    private val projectGithubRepoRepository: ProjectGithubRepoRepository,
    private val projectGithubRepoEventPublisher: ProjectGithubRepoEventPublisher,
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun apply(channelId: Long, projectId: Long?, deleted: Boolean, occurredAt: Instant) {
        val version = occurredAt.toProjectionPrecision()
        val projection = channelProjectionRepository.findByIdForUpdate(channelId)
        val accepted = if (projection == null) {
            channelProjectionRepository.save(
                ChannelProjection(
                    channelId = channelId,
                    projectId = projectId,
                    deleted = deleted,
                    sourceOccurredAt = version,
                ),
            )
            true
        } else {
            projection.apply(projectId, deleted, version).also { applied ->
                if (applied) channelProjectionRepository.save(projection)
            }
        }
        if (!accepted) return

        val danglingLinks = projectGithubRepoRepository.findAllByGithubWebhookChannelIdForUpdate(channelId)
            .filter { deleted || it.projectId != projectId }
        if (danglingLinks.isEmpty()) return

        val repoOccurredAt = Instant.now().toProjectionPrecision()
        danglingLinks.forEach { repoLink ->
            repoLink.clearWebhookChannel()
            projectGithubRepoRepository.save(repoLink)
            projectGithubRepoEventPublisher.publishUpsert(repoLink, repoOccurredAt)
        }
    }
}
