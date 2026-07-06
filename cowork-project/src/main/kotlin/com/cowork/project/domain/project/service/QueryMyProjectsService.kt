package com.cowork.project.domain.project.service

import com.cowork.project.domain.project.presentation.data.response.ProjectResDto
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable

interface QueryMyProjectsService {
    fun execute(userId: Long, pageable: Pageable): Page<ProjectResDto>
}
