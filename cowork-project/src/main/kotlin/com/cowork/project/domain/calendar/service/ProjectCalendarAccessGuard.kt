package com.cowork.project.domain.calendar.service

import com.cowork.project.domain.calendar.model.CalendarAction
import com.cowork.project.domain.calendar.model.CalendarPermissionPolicy
import com.cowork.project.domain.membership.TeamRolePermissionReader
import com.cowork.project.domain.project.entity.Project
import com.cowork.project.domain.project.repository.ProjectRepository
import com.cowork.project.domain.project.service.ProjectAccessGuard
import com.cowork.project.global.policy.PermissionKeys
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import team.themoment.sdk.exception.ExpectedException

/** 프로젝트 일정과 카테고리 권한 검사. 조회는 프로젝트 읽기, 생성과 수정은 여기에 팀 일정 권한을 더한다. */
@Component
class ProjectCalendarAccessGuard(
    private val projectAccessGuard: ProjectAccessGuard,
    private val teamRolePermissionReader: TeamRolePermissionReader,
    private val projectRepository: ProjectRepository,
) {
    fun requireReader(projectId: Long, userId: Long): Project = projectAccessGuard.findProjectOrThrow(projectId).also {
        projectAccessGuard.requireProjectReader(it, userId)
    }

    fun requireCreator(projectId: Long, userId: Long): Project = requireReader(projectId, userId).also {
        if (!teamRolePermissionReader.hasPermission(it.teamId, userId, PermissionKeys.CALENDAR_EVENT_CREATE)) {
            throw ExpectedException("일정 만들기 권한이 없습니다.", HttpStatus.FORBIDDEN)
        }
    }

    /** 작성자가 아니면 [action]에 맞는 팀 권한을 요구한다. 프로젝트 읽기 권한은 호출 전에 확인한다. */
    fun requireOwnerOrPermission(project: Project, createdBy: Long, userId: Long, action: CalendarAction) {
        val key = when (action) {
            CalendarAction.UPDATE -> PermissionKeys.CALENDAR_EVENT_UPDATE
            CalendarAction.DELETE -> PermissionKeys.CALENDAR_EVENT_DELETE
        }
        val allowed = CalendarPermissionPolicy.allows(createdBy, userId) {
            teamRolePermissionReader.hasPermission(project.teamId, userId, key)
        }
        if (allowed) return
        throw ExpectedException(
            if (action == CalendarAction.UPDATE) "다른 사람의 일정을 수정할 권한이 없습니다." else "다른 사람의 일정을 삭제할 권한이 없습니다.",
            HttpStatus.FORBIDDEN,
        )
    }

    fun readerIds(project: Project): Set<Long> = projectRepository.findReadableUserIds(project.id).toSet()

    /** 참석자는 프로젝트를 읽을 수 있는 팀 멤버만 고를 수 있다. */
    fun requireAttendees(project: Project, attendeeUserIds: Collection<Long>) {
        if (attendeeUserIds.isEmpty()) return
        if (!readerIds(project).containsAll(attendeeUserIds)) {
            throw ExpectedException("참석자는 프로젝트를 볼 수 있는 팀 멤버여야 합니다.", HttpStatus.BAD_REQUEST)
        }
    }
}
