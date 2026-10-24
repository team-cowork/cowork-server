package com.cowork.project.domain.project.service.impl

import com.cowork.project.domain.project.event.ProjectEventPublisher
import com.cowork.project.domain.project.presentation.data.response.ProjectResDto
import com.cowork.project.domain.project.repository.ProjectRepository
import com.cowork.project.domain.project.service.ProjectAccessGuard
import com.cowork.project.domain.project.service.ReorderTeamProjectsService
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException

@Service
class ReorderTeamProjectsServiceImpl(
    private val projectRepository: ProjectRepository,
    private val projectAccessGuard: ProjectAccessGuard,
    private val projectEventPublisher: ProjectEventPublisher,
) : ReorderTeamProjectsService {

    @Transactional
    override fun execute(userId: Long, teamId: Long, orderedProjectIds: List<Long>): List<ProjectResDto> {
        projectAccessGuard.requireTeamMember(teamId, userId)

        if (orderedProjectIds.isEmpty()) {
            throw ExpectedException("프로젝트 순서 목록은 비어 있을 수 없습니다.", HttpStatus.BAD_REQUEST)
        }
        val inputIds = orderedProjectIds.toSet()
        if (inputIds.size != orderedProjectIds.size) {
            throw ExpectedException("프로젝트 순서 목록에 중복 ID가 포함되어 있습니다.", HttpStatus.BAD_REQUEST)
        }

        val lockedProjects = projectRepository.findAllByTeamIdForUpdate(teamId)
        val permissions = projectAccessGuard.permissionsForProjects(lockedProjects, userId)
        val projects = lockedProjects.filter { permissions.getValue(it.id).read }
        val teamProjectIds = projects.map { it.id }.toSet()
        if (inputIds != teamProjectIds) {
            throw ExpectedException("현재 조회 가능한 모든 프로젝트 ID를 정확히 포함해야 합니다.", HttpStatus.BAD_REQUEST)
        }

        val positionSlots = projects.map { it.position }.sorted()
        val projectById = projects.associateBy { it.id }
        val movedProjects = orderedProjectIds.mapIndexedNotNull { index, projectId ->
            projectById.getValue(projectId).takeIf { it.position != positionSlots[index] }
        }
        if (movedProjects.any { !permissions.getValue(it.id).reorder }) {
            throw ExpectedException("프로젝트 재정렬 권한이 없습니다.", HttpStatus.FORBIDDEN)
        }
        orderedProjectIds.forEachIndexed { index, projectId ->
            projectById.getValue(projectId).updatePosition(positionSlots[index])
        }

        val orderedProjects = orderedProjectIds.mapNotNull(projectById::get)
        movedProjects.forEach(projectEventPublisher::publishUpdated)
        return orderedProjects.map(ProjectResDto::of)
    }
}
