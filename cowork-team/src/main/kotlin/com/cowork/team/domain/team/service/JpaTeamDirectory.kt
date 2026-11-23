package com.cowork.team.domain.team.service

import com.cowork.team.domain.team.repository.TeamRepository
import com.cowork.team.domain.teamAccess.LockedTeam
import com.cowork.team.domain.teamAccess.TeamDirectory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Component
class JpaTeamDirectory(private val teamRepository: TeamRepository) : TeamDirectory {
    @Transactional(readOnly = true)
    override fun exists(teamId: Long) = teamRepository.existsById(teamId)

    @Transactional(propagation = Propagation.MANDATORY)
    override fun lock(teamId: Long): LockedTeam? = teamRepository.findByIdForUpdate(teamId)?.let {
        LockedTeam(it.id, it.name, it.ownerId)
    }
}
