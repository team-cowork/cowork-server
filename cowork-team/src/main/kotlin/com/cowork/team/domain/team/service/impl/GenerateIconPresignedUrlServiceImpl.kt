package com.cowork.team.domain.team.service.impl

import com.cowork.team.domain.team.presentation.data.response.IconPresignedUrlResponse
import com.cowork.team.domain.team.service.GenerateIconPresignedUrlService
import com.cowork.team.domain.team.service.TeamAccessGuard
import com.cowork.team.domain.team.service.TeamIconOwnershipManager
import org.springframework.stereotype.Service

@Service
class GenerateIconPresignedUrlServiceImpl(
    private val teamAccessGuard: TeamAccessGuard,
    private val teamIconOwnershipManager: TeamIconOwnershipManager,
) : GenerateIconPresignedUrlService {
    override fun execute(userId: Long, teamId: Long, contentType: String): IconPresignedUrlResponse {
        // 권한이 없는 사용자는 스토리지 업로드 URL을 받지 못한다.
        teamAccessGuard.requirePermission(teamId, userId, "manage_team")
        return teamIconOwnershipManager.issue(userId, teamId, contentType)
    }
}
