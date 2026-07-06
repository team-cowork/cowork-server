package com.cowork.project.domain.project.service

import com.cowork.project.domain.project.presentation.data.response.ProjectDetailResDto

interface GetProjectService {
    fun execute(userId: Long, projectId: Long): ProjectDetailResDto
}
