package com.cowork.project.domain.project.service

interface IsProjectMemberService {
    fun isMember(projectId: Long, userId: Long): Boolean
}
