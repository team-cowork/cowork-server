package com.cowork.team.domain.team.service.impl

import com.cowork.team.domain.team.presentation.data.response.IconConfirmResponse
import com.cowork.team.domain.team.service.S3Service
import com.cowork.team.domain.team.service.TeamAccessGuard
import com.cowork.team.domain.team.service.UpdateTeamIconService
import com.cowork.team.domain.teamRole.entity.TeamRole
import com.cowork.team.global.support.afterCommit
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional
class UpdateTeamIconServiceImpl(private val s3Service: S3Service, private val teamAccessGuard: TeamAccessGuard) :
    UpdateTeamIconService {

    override fun updateIcon(userId: Long, teamId: Long, iconUrl: String): IconConfirmResponse {
        s3Service.validateIconUrl(iconUrl)
        teamAccessGuard.requireRole(teamId, userId, TeamRole.OWNER, TeamRole.ADMIN)
        val team = teamAccessGuard.findTeamOrThrow(teamId)
        val previousIconUrl = team.iconUrl

        if (previousIconUrl != iconUrl) {
            team.iconUrl = iconUrl
            previousIconUrl?.let { prev ->
                val key = s3Service.extractObjectKey(prev)
                afterCommit { s3Service.deleteObject(key) }
            }
        }
        return IconConfirmResponse(iconUrl = iconUrl)
    }
}
