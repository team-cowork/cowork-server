package com.cowork.project.domain.project.service

import com.cowork.project.domain.project.presentation.data.response.ProjectResDto

interface ReorderTeamProjectsService {
    fun reorderTeamProjects(userId: Long, teamId: Long, orderedProjectIds: List<Long>): List<ProjectResDto>
}
