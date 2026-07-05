package com.cowork.project.domain.project.service

import com.cowork.project.domain.project.presentation.data.response.ProjectResDto
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable

interface GetProjectsByTeamIdService {
    fun getProjectsByTeamId(userId: Long, teamId: Long, pageable: Pageable): Page<ProjectResDto>
}
