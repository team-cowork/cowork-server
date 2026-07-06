package com.cowork.project.domain.project.service.impl

import com.cowork.project.domain.project.service.ProjectAccessGuard
import com.cowork.project.domain.project.service.QueryProjectTeamIdService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class QueryProjectTeamIdServiceImpl(private val projectAccessGuard: ProjectAccessGuard) : QueryProjectTeamIdService {

    override fun execute(projectId: Long): Long = projectAccessGuard.findProjectOrThrow(projectId).teamId
}
