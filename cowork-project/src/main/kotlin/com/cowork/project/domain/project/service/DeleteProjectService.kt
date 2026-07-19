package com.cowork.project.domain.project.service

interface DeleteProjectService {
    fun execute(userId: Long, projectId: Long)
}
