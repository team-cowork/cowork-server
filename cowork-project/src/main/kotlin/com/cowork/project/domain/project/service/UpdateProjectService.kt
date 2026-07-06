package com.cowork.project.domain.project.service

import com.cowork.project.domain.project.presentation.data.request.UpdateProjectReqDto
import com.cowork.project.domain.project.presentation.data.response.ProjectResDto

interface UpdateProjectService {
    fun execute(userId: Long, projectId: Long, request: UpdateProjectReqDto): ProjectResDto
}
