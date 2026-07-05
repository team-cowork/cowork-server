package com.cowork.project.domain.project.service

interface DeleteProjectService {
    fun deleteProject(userId: Long, projectId: Long)
}
