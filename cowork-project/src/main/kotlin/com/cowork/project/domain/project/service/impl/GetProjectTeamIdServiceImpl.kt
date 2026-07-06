package com.cowork.project.domain.project.service.impl

import com.cowork.project.domain.project.service.GetProjectTeamIdService
import com.cowork.project.domain.project.service.ProjectAccessGuard
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class GetProjectTeamIdServiceImpl(private val projectAccessGuard: ProjectAccessGuard) : GetProjectTeamIdService {

    override fun execute(projectId: Long): Long = projectAccessGuard.findProjectOrThrow(projectId).teamId
}
