package com.cowork.team.domain.teamMember.service.impl

import com.cowork.team.domain.team.event.TeamMemberEventPublisher
import com.cowork.team.domain.team.projection.TeamSettingProjectionReader
import com.cowork.team.domain.team.repository.TeamRepository
import com.cowork.team.domain.teamMember.JoinTeamResponse
import com.cowork.team.domain.teamMember.RegisterTeamMemberService
import com.cowork.team.domain.teamMember.entity.TeamMember
import com.cowork.team.domain.teamMember.repository.TeamMemberRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException

@Service
class RegisterTeamMemberServiceImpl(
    private val teamRepository: TeamRepository,
    private val teamMemberRepository: TeamMemberRepository,
    private val teamMemberEventPublisher: TeamMemberEventPublisher,
    private val teamSettingProjectionReader: TeamSettingProjectionReader,
) : RegisterTeamMemberService {

    @Transactional
    override fun execute(teamId: Long, userId: Long): JoinTeamResponse {
        val team = teamRepository.findByIdForUpdate(teamId)
            ?: throw ExpectedException("팀을 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
        if (teamMemberRepository.existsByTeamIdAndUserId(teamId, userId)) {
            throw ExpectedException("이미 팀 멤버입니다.", HttpStatus.CONFLICT)
        }

        val nicknameFormat = teamSettingProjectionReader.getNicknameFormat(teamId)

        val member = teamMemberRepository.save(TeamMember(team = team, userId = userId))
        teamMemberEventPublisher.publishUpsert(member)

        return JoinTeamResponse(
            teamId = teamId,
            userId = userId,
            role = member.role.name,
            joinedAt = requireNotNull(member.joinedAt),
            nicknameFormatEnforced = nicknameFormat.enforced,
            nicknameFormatExample = nicknameFormat.example,
        )
    }
}
