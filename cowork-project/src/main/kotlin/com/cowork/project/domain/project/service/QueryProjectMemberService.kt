package com.cowork.project.domain.project.service

interface QueryProjectMemberService {
    fun execute(projectId: Long, userId: Long): Boolean
}
