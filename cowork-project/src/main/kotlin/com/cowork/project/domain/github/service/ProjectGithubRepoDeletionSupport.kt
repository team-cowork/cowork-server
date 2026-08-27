package com.cowork.project.domain.github.service

import com.cowork.project.domain.github.event.ProjectGithubRepoEventPublisher
import com.cowork.project.domain.github.repository.ProjectGithubRepoRepository
import com.cowork.project.domain.githubPreference.event.GithubRepoSettingCommandPublisher
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Component
class ProjectGithubRepoDeletionSupport(
    private val repository: ProjectGithubRepoRepository,
    private val eventPublisher: ProjectGithubRepoEventPublisher,
    private val settingCommandPublisher: GithubRepoSettingCommandPublisher,
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun deleteByProjectIds(projectIds: Collection<Long>, occurredAt: Instant) {
        if (projectIds.isEmpty()) return
        val repoLinks = repository.findAllByProjectIdInForUpdate(projectIds.distinct())
        if (repoLinks.isEmpty()) return

        repository.deleteAll(repoLinks)
        repoLinks.forEach {
            eventPublisher.publishDelete(it, occurredAt)
            settingCommandPublisher.publishDelete(it.id, requestedBy = null, occurredAt)
        }
    }
}
