package com.cowork.project.domain.project.service.impl

import com.cowork.project.domain.project.presentation.data.response.ProjectResDto
import com.cowork.project.domain.project.repository.ProjectRepository
import com.cowork.project.domain.project.service.ProjectListSortPolicy
import com.cowork.project.domain.project.service.QueryMyProjectsService
import com.cowork.project.global.projection.ProjectionReadinessGate
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class QueryMyProjectsServiceImpl(
    private val projectRepository: ProjectRepository,
    private val readiness: ProjectionReadinessGate,
) : QueryMyProjectsService {

    @Transactional(readOnly = true)
    override fun execute(userId: Long, pageable: Pageable): Page<ProjectResDto> {
        readiness.requireReady()
        ProjectListSortPolicy.requireAllowed(pageable.sort)
        return projectRepository.findReadableByUserId(userId, pageable).map { ProjectResDto.of(it) }
    }
}
