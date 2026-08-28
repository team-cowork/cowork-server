package com.cowork.project.domain.githubPreference.service

import com.cowork.project.domain.githubPreference.entity.GithubRepoPreferenceProjection
import com.cowork.project.domain.githubPreference.entity.GithubRepoSettingOperationStatus
import com.cowork.project.domain.githubPreference.repository.GithubRepoPreferenceProjectionRepository
import com.cowork.project.domain.githubPreference.repository.GithubRepoSettingOperationRepository
import com.cowork.project.global.projection.toProjectionPrecision
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Component
class GithubRepoPreferenceProjectionHandler(
    private val repository: GithubRepoPreferenceProjectionRepository,
    private val operationRepository: GithubRepoSettingOperationRepository,
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun apply(repoId: Long, labelAutoApply: Boolean, deleted: Boolean, occurredAt: Instant) {
        val version = occurredAt.toProjectionPrecision()
        val projection = repository.findByIdForUpdate(repoId)
        val accepted = if (projection == null) {
            repository.save(
                GithubRepoPreferenceProjection(
                    repoId = repoId,
                    labelAutoApply = labelAutoApply,
                    deleted = deleted,
                    sourceOccurredAt = version,
                ),
            )
            true
        } else {
            projection.apply(labelAutoApply, deleted, version).also { applied ->
                if (applied) repository.save(projection)
            }
        }
        if (!accepted) return

        operationRepository.findAllByRepoIdAndStatusForUpdate(
            repoId,
            GithubRepoSettingOperationStatus.PROCESSING,
        ).filter { it.completeIfObserved(labelAutoApply, deleted, version) }
            .forEach(operationRepository::save)
    }
}
