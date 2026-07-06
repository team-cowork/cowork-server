package com.cowork.project.domain.project.service

interface GetProjectTeamIdService {
    fun execute(projectId: Long): Long
}
