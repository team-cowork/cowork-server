package com.cowork.project.domain.project.service.impl

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

        val projects = projectRepository.findAllByTeamIdOrderByPositionAscIdAsc(teamId)
        val teamProjectIds = projects.map { it.id }.toSet()
        if (inputIds != teamProjectIds) {
            throw ExpectedException("팀의 모든 프로젝트 ID를 정확히 포함해야 합니다.", HttpStatus.BAD_REQUEST)
        }

        val projectById = projects.associateBy { it.id }
        orderedProjectIds.forEachIndexed { index, projectId ->
            projectById[projectId]?.updatePosition(index)
        }

        return orderedProjectIds.mapNotNull { projectById[it] }.map(ProjectResDto::of)
    }
}
