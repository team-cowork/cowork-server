package com.cowork.project.domain.project.service

import com.cowork.project.domain.project.presentation.data.response.ProjectAccessUserResponse

interface QueryProjectMembersService {
    fun execute(userId: Long, projectId: Long): List<ProjectAccessUserResponse>
}
