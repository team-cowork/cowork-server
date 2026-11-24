package com.cowork.team.domain.teamMember

import com.cowork.team.domain.teamMember.event.TeamMemberEventPublisher
import com.cowork.team.domain.teamMember.repository.TeamMemberEventStateRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Service
class TeamMemberSnapshots(
    private val teamMemberEventStateRepository: TeamMemberEventStateRepository,
    private val teamMemberEventPublisher: TeamMemberEventPublisher,
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun publishBatch(afterTeamId: Long, afterUserId: Long, pageSize: Int): Pair<Long, Long>? {
        val states = teamMemberEventStateRepository.findSnapshotBatch(
            afterTeamId,
            afterUserId,
            PageRequest.of(0, pageSize),
        )
        states.forEach(teamMemberEventPublisher::publishSnapshot)
        return states.lastOrNull()?.let { it.teamId to it.userId }
    }
}
