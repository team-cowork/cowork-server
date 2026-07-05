package com.cowork.project.domain.project.service

interface RemoveProjectMemberService {
    fun removeMember(userId: Long, projectId: Long, memberId: Long)
}
