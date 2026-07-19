package com.cowork.project.domain.project.service

import com.cowork.project.domain.project.presentation.data.request.CreateProjectReqDto
import com.cowork.project.domain.project.presentation.data.response.ProjectResDto

interface CreateProjectService {
    fun execute(userId: Long, request: CreateProjectReqDto): ProjectResDto
}
