package com.cowork.team.service

import com.cowork.global.error.BusinessException
import com.cowork.team.domain.TeamMember
import com.cowork.team.domain.TeamMemberRole
import com.cowork.team.domain.TeamPermission
import com.cowork.team.repository.TeamMemberRepository
import com.cowork.team.repository.TeamRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component

@Component
class TeamAccessChecker(
    private val teamRepository: TeamRepository,
    private val teamMemberRepository: TeamMemberRepository,
) {

    fun requireTeamExists(teamId: Long) {
        if (!teamRepository.existsById(teamId)) {
            throw BusinessException(HttpStatus.NOT_FOUND, "팀을 찾을 수 없습니다.")
        }
    }

    fun isMember(teamId: Long, userId: Long): Boolean =
        teamMemberRepository.existsByTeamIdAndUserId(teamId, userId)

    fun requireMember(teamId: Long, userId: Long): TeamMember {
        requireTeamExists(teamId)
        return teamMemberRepository.findByTeamIdAndUserId(teamId, userId)
            ?: throw BusinessException(HttpStatus.FORBIDDEN, "팀 멤버가 아닙니다.")
    }

    fun requireOwner(teamId: Long, userId: Long): TeamMember {
        val member = requireMember(teamId, userId)
        if (member.role != TeamMemberRole.OWNER) {
            throw BusinessException(HttpStatus.FORBIDDEN, "팀 소유자만 수행할 수 있습니다.")
        }
        return member
    }

    @Suppress("UNUSED_PARAMETER")
    fun hasPermission(member: TeamMember, permission: TeamPermission): Boolean = member.role.isManager

    fun requirePermission(teamId: Long, userId: Long, permission: TeamPermission): TeamMember {
        val member = requireMember(teamId, userId)
        if (!hasPermission(member, permission)) {
            throw BusinessException(HttpStatus.FORBIDDEN, "권한이 없습니다.")
        }
        return member
    }
}
