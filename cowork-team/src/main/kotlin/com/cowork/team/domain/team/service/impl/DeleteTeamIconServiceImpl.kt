package com.cowork.team.domain.team.service.impl

import com.cowork.team.domain.team.service.DeleteTeamIconService
import com.cowork.team.domain.team.service.S3Service
import com.cowork.team.domain.team.service.TeamAccessGuard
import com.cowork.team.domain.teamRole.entity.TeamRole
import com.cowork.team.global.support.afterCommit
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException

@Service
@Transactional
class DeleteTeamIconServiceImpl(
    private val s3Service: S3Service,
    private val teamAccessGuard: TeamAccessGuard,
) : DeleteTeamIconService {

    override fun deleteIcon(userId: Long, teamId: Long) {
        teamAccessGuard.requireRole(teamId, userId, TeamRole.OWNER, TeamRole.ADMIN)
        val team = teamAccessGuard.findTeamOrThrow(teamId)
        val previousIconUrl = team.iconUrl
            ?: throw ExpectedException("아이콘이 없습니다.", HttpStatus.NOT_FOUND)
        team.iconUrl = null

        val key = s3Service.extractObjectKey(previousIconUrl)
        afterCommit { s3Service.deleteObject(key) }
    }
}
