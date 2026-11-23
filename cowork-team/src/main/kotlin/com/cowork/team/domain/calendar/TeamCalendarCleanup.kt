package com.cowork.team.domain.calendar

import com.cowork.team.domain.calendar.repository.TeamCalendarEventRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Service
class TeamCalendarCleanup(private val teamCalendarEventRepository: TeamCalendarEventRepository) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun removeAll(teamId: Long) {
        teamCalendarEventRepository.deleteAll(teamCalendarEventRepository.findAllByTeamId(teamId))
    }
}
