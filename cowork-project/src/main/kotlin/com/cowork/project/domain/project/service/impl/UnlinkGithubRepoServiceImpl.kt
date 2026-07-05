package com.cowork.project.domain.project.service.impl

import com.cowork.project.domain.project.presentation.data.response.ProjectDetailResDto
import com.cowork.project.domain.project.service.ProjectAccessGuard
import com.cowork.project.domain.project.service.UnlinkGithubRepoService
import com.cowork.project.domain.projectMember.repository.ProjectMemberRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional
class UnlinkGithubRepoServiceImpl(
    private val projectMemberRepository: ProjectMemberRepository,
    private val projectAccessGuard: ProjectAccessGuard,
) : UnlinkGithubRepoService {

    override fun unlinkGithubRepo(userId: Long, projectId: Long): ProjectDetailResDto {
        val project = projectAccessGuard.findProjectOrThrow(projectId)
        projectAccessGuard.requireProjectModifier(project, userId)

        project.unlinkGithubRepo()

        val memberCount = projectMemberRepository.countByProjectId(projectId)
        return ProjectDetailResDto.of(project, memberCount)
    }
}
