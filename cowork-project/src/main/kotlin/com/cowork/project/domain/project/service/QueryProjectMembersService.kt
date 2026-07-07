package com.cowork.project.domain.project.service

import com.cowork.project.domain.projectMember.presentation.data.response.ProjectMemberResDto

interface QueryProjectMembersService {
    fun execute(userId: Long, projectId: Long): List<ProjectMemberResDto>
}
