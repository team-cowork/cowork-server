package com.cowork.project.domain.project.service

import com.cowork.project.domain.projectMember.presentation.data.request.AddProjectMemberReqDto
import com.cowork.project.domain.projectMember.presentation.data.response.ProjectMemberResDto

interface AddProjectMemberService {
    fun execute(userId: Long, projectId: Long, request: AddProjectMemberReqDto): ProjectMemberResDto
}
