package com.cowork.project.domain.user.service

import com.cowork.project.domain.user.repository.UserProfileProjectionRepository
import com.cowork.project.global.projection.ProjectionReadinessGate
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import team.themoment.sdk.exception.ExpectedException

@Component
class UserProfileProjectionReader(
    private val repository: UserProfileProjectionRepository,
    private val readinessGate: ProjectionReadinessGate,
) {
    fun resolveGithubId(userId: Long): String {
        readinessGate.requireReady()
        var profile = repository.findById(userId).orElse(null)
        if (profile == null) {
            readinessGate.requireCurrent()
            profile = repository.findById(userId).orElseThrow(::profileNotFound)
        }
        if (profile.deleted) {
            throw profileNotFound()
        }
        return profile.githubId
            ?: throw ExpectedException(
                "GitHub 계정이 연동되어 있지 않습니다. 계정 설정에서 GitHub 계정을 연동해주세요.",
                HttpStatus.BAD_REQUEST,
            )
    }

    fun resolveUniqueUserId(githubId: String): Long {
        readinessGate.requireReady()
        var matches = repository.findAllByGithubIdAndDeletedFalse(githubId)
        if (matches.isEmpty()) {
            readinessGate.requireCurrent()
            matches = repository.findAllByGithubIdAndDeletedFalse(githubId)
            if (matches.isEmpty()) throw profileNotFound()
        }
        if (matches.size != 1) throw profileSynchronizing()
        return matches.single().userId
    }

    private fun profileNotFound() = ExpectedException(
        "사용자 정보를 찾을 수 없습니다.",
        HttpStatus.NOT_FOUND,
    )

    private fun profileSynchronizing() = ExpectedException(
        "사용자 프로필 상태를 아직 동기화하는 중입니다.",
        HttpStatus.SERVICE_UNAVAILABLE,
    )
}
