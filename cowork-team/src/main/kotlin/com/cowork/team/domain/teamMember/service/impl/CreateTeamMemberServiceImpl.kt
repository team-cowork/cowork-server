package com.cowork.team.domain.teamMember.service.impl

import com.cowork.team.domain.teamAccess.TeamDirectory
import com.cowork.team.domain.teamMember.CreateTeamMemberService
import com.cowork.team.domain.teamMember.RegisteredTeamMember
import com.cowork.team.domain.teamMember.entity.TeamMember
import com.cowork.team.domain.teamMember.event.TeamMemberEventPublisher
import com.cowork.team.domain.teamMember.projection.TeamSettingProjectionReader
import com.cowork.team.domain.teamMember.repository.TeamMemberRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException

@Service
class CreateTeamMemberServiceImpl(
    private val teamDirectory: TeamDirectory,
    private val teamMemberRepository: TeamMemberRepository,
    private val teamMemberEventPublisher: TeamMemberEventPublisher,
    private val teamSettingProjectionReader: TeamSettingProjectionReader,
) : CreateTeamMemberService {

    @Transactional
    override fun execute(teamId: Long, userId: Long): RegisteredTeamMember {
        val team = teamDirectory.lockOrThrow(teamId)
        if (teamMemberRepository.existsByTeamIdAndUserId(teamId, userId)) {
            throw ExpectedException("이미 팀 멤버입니다.", HttpStatus.CONFLICT)
        }

        val nicknameFormat = teamSettingProjectionReader.getNicknameFormat(teamId)

        val member = teamMemberRepository.save(TeamMember(teamId = team.id, userId = userId))
        teamMemberEventPublisher.publishUpsert(member)

        return RegisteredTeamMember(
            teamId = teamId,
            userId = userId,
            role = member.role.name,
            joinedAt = requireNotNull(member.joinedAt),
            nicknameFormatEnforced = nicknameFormat.enforced,
            nicknameFormatExample = nicknameFormat.example,
        )
    }
}
