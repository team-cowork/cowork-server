package com.cowork.team.domain.teamMember.service

import com.cowork.team.domain.teamAccess.MembershipDirectory
import com.cowork.team.domain.teamMember.repository.TeamMemberEventStateRepository
import com.cowork.team.domain.teamMember.repository.TeamMemberRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException
import java.time.Instant

@Component
class JpaMembershipDirectory(
    private val teamMemberRepository: TeamMemberRepository,
    private val teamMemberEventStateRepository: TeamMemberEventStateRepository,
) : MembershipDirectory {
    @Transactional(readOnly = true)
    override fun exists(teamId: Long, userId: Long) = teamMemberRepository.existsByTeamIdAndUserId(teamId, userId)

    @Transactional(readOnly = true)
    override fun memberIds(teamId: Long) =
        teamMemberRepository.findAllByTeamId(teamId).mapTo(mutableSetOf()) { it.userId }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun activeVersionForUpdate(teamId: Long, userId: Long): Instant {
        val state = teamMemberEventStateRepository.findByKeyForUpdate(teamId, userId)
            ?: throw ExpectedException("해당 멤버 상태를 찾을 수 없습니다.", HttpStatus.CONFLICT)
        if (state.deleted) {
            throw ExpectedException("해당 멤버를 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
        }
        return state.stateOccurredAt
    }
}
