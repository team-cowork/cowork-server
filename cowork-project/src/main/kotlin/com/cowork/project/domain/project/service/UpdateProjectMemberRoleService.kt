package com.cowork.project.domain.project.service

import com.cowork.project.domain.projectMember.presentation.data.request.UpdateProjectMemberRoleReqDto
import com.cowork.project.domain.projectMember.presentation.data.response.ProjectMemberResDto

interface UpdateProjectMemberRoleService {
    fun execute(
        userId: Long,
        projectId: Long,
        memberId: Long,
        request: UpdateProjectMemberRoleReqDto,
    ): ProjectMemberResDto
}
