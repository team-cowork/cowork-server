package com.cowork.project.domain.project.service

interface QueryProjectTeamIdService {
    fun execute(projectId: Long): Long
}
