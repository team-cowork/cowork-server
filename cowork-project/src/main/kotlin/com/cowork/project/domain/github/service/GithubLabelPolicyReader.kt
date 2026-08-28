package com.cowork.project.domain.github.service

import com.cowork.project.domain.githubPreference.repository.GithubRepoPreferenceProjectionRepository
import com.cowork.project.global.projection.ProjectionReadinessGate
import org.springframework.stereotype.Component

/**
 * cowork-preference의 GITHUB_REPO 설정 state topic을 반영한 로컬 projection에서 정책을 읽는다.
 * authoritative row가 없거나 DELETE 상태면 preference의 기본값인 자동 적용으로 처리한다.
 */
@Component
class GithubLabelPolicyReader(
    private val repository: GithubRepoPreferenceProjectionRepository,
    private val readinessGate: ProjectionReadinessGate,
) {
    fun readAutoApply(repoId: Long): Boolean {
        readinessGate.requireReady()
        return repository.findById(repoId).orElse(null)
            ?.takeUnless { it.deleted }
            ?.labelAutoApply
            ?: LABEL_AUTO_APPLY_DEFAULT
    }

    fun readAutoApplyBulk(repoIds: List<Long>): Map<Long, Boolean> {
        if (repoIds.isEmpty()) return emptyMap()
        readinessGate.requireReady()
        val preferencesById = repository.findAllById(repoIds)
            .filterNot { it.deleted }
            .associateBy { it.repoId }
        return repoIds.associateWith { repoId ->
            preferencesById[repoId]?.labelAutoApply ?: LABEL_AUTO_APPLY_DEFAULT
        }
    }

    private companion object {
        const val LABEL_AUTO_APPLY_DEFAULT = true
    }
}
