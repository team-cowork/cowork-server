package com.cowork.project.domain.project.service

interface RemoveProjectMemberService {
    fun execute(userId: Long, projectId: Long, memberId: Long)
}
