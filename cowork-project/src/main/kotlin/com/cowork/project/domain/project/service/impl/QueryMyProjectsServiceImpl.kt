package com.cowork.project.domain.project.service.impl

import com.cowork.project.domain.project.presentation.data.response.ProjectResDto
import com.cowork.project.domain.project.repository.ProjectRepository
import com.cowork.project.domain.project.service.QueryMyProjectsService
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class QueryMyProjectsServiceImpl(private val projectRepository: ProjectRepository) : QueryMyProjectsService {

    override fun execute(userId: Long, pageable: Pageable): Page<ProjectResDto> =
        projectRepository.findProjectsByMemberUserId(userId, pageable)
            .map { ProjectResDto.of(it) }
}
