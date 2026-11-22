package com.cowork.team.domain.calendar.service

import com.cowork.team.domain.calendar.model.CalendarAction
import com.cowork.team.domain.calendar.model.CalendarPermissionPolicy
import com.cowork.team.domain.team.service.TeamAccessGuard
import com.cowork.team.domain.teamMember.repository.TeamMemberRepository
import com.cowork.team.domain.teamRole.projection.TeamRoleProjectionReader
import com.cowork.team.global.policy.PermissionKeys
import com.cowork.team.global.policy.RolePolicy
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import team.themoment.sdk.exception.ExpectedException

/** 팀 캘린더 권한 검사. 조회는 팀 멤버, 생성은 일정 만들기 권한, 다른 사람 일정의 수정과 삭제는 그 권한을 요구한다. */
@Component
class TeamCalendarAccessGuard(
    private val teamAccessGuard: TeamAccessGuard,
    private val teamMemberRepository: TeamMemberRepository,
    private val teamRoleProjectionReader: TeamRoleProjectionReader,
) {
    fun requireMember(teamId: Long, userId: Long) {
        teamAccessGuard.findTeamOrThrow(teamId)
        teamAccessGuard.requireMemberExists(teamId, userId)
    }

    fun requireCreator(teamId: Long, userId: Long) {
        requireMember(teamId, userId)
        if (!hasPermission(teamId, userId, PermissionKeys.CALENDAR_EVENT_CREATE)) {
            throw ExpectedException("일정 만들기 권한이 없습니다.", HttpStatus.FORBIDDEN)
        }
    }

    /** 작성자가 아니면 [action]에 맞는 팀 권한을 요구한다. 팀 멤버인지는 호출 전에 확인한다. */
    fun requireOwnerOrPermission(teamId: Long, createdBy: Long, userId: Long, action: CalendarAction) {
        val key = when (action) {
            CalendarAction.UPDATE -> PermissionKeys.CALENDAR_EVENT_UPDATE
            CalendarAction.DELETE -> PermissionKeys.CALENDAR_EVENT_DELETE
        }
        if (CalendarPermissionPolicy.allows(createdBy, userId) { hasPermission(teamId, userId, key) }) return
        throw ExpectedException(
            if (action == CalendarAction.UPDATE) "다른 사람의 일정을 수정할 권한이 없습니다." else "다른 사람의 일정을 삭제할 권한이 없습니다.",
            HttpStatus.FORBIDDEN,
        )
    }

    fun memberIds(teamId: Long): Set<Long> = teamMemberRepository.findAllByTeamId(teamId).mapTo(mutableSetOf()) {
        it.userId
    }

    /** 참석자는 팀 멤버만 고를 수 있다. */
    fun requireAttendees(teamId: Long, attendeeUserIds: Collection<Long>) {
        if (attendeeUserIds.isEmpty()) return
        if (!memberIds(teamId).containsAll(attendeeUserIds)) {
            throw ExpectedException("참석자는 팀 멤버여야 합니다.", HttpStatus.BAD_REQUEST)
        }
    }

    private fun hasPermission(teamId: Long, userId: Long, key: String): Boolean =
        teamRoleProjectionReader.getMemberRoles(teamId, userId).any { RolePolicy.allows(it.permissions, key) }
}
