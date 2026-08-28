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
    private val channelRepository: ChannelProjectionRepository,
    private val repoLinkRepository: ProjectGithubRepoRepository,
    private val repoEventPublisher: ProjectGithubRepoEventPublisher,
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun apply(channelId: Long, projectId: Long?, deleted: Boolean, occurredAt: Instant) {
        val version = occurredAt.toProjectionPrecision()
        val projection = channelRepository.findByIdForUpdate(channelId)
        val accepted = if (projection == null) {
            channelRepository.save(
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
                if (applied) channelRepository.save(projection)
            }
        }
        if (!accepted) return

        val danglingLinks = repoLinkRepository.findAllByGithubWebhookChannelIdForUpdate(channelId)
            .filter { deleted || it.projectId != projectId }
        if (danglingLinks.isEmpty()) return

        val repoOccurredAt = Instant.now().toProjectionPrecision()
        danglingLinks.forEach { repoLink ->
            repoLink.clearWebhookChannel()
            repoLinkRepository.save(repoLink)
            repoEventPublisher.publishUpsert(repoLink, repoOccurredAt)
        }
    }
}
