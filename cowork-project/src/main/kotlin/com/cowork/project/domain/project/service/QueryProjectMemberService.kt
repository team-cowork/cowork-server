package com.cowork.project.domain.project.service

interface QueryProjectMemberService {
    fun isMember(projectId: Long, userId: Long): Boolean
}
