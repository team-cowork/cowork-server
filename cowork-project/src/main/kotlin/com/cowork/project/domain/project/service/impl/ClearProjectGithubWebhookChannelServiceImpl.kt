package com.cowork.project.domain.project.service.impl

import com.cowork.project.domain.github.presentation.data.response.ProjectGithubRepoResDto
import com.cowork.project.domain.github.repository.ProjectGithubRepoRepository
import com.cowork.project.domain.project.service.ClearProjectGithubWebhookChannelService
import com.cowork.project.domain.project.service.ProjectAccessGuard
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException

@Service
class ClearProjectGithubWebhookChannelServiceImpl(
    private val projectGithubRepoRepository: ProjectGithubRepoRepository,
    private val projectAccessGuard: ProjectAccessGuard,
) : ClearProjectGithubWebhookChannelService {

    @Transactional
    override fun execute(userId: Long, projectId: Long, repoId: Long): ProjectGithubRepoResDto {
        val project = projectAccessGuard.findProjectOrThrow(projectId)
        projectAccessGuard.requireProjectModifier(project, userId)
        val repoLink = projectGithubRepoRepository.findByIdAndProjectId(repoId, projectId)
            ?: throw ExpectedException("등록된 레포를 찾을 수 없습니다.", HttpStatus.NOT_FOUND)

        repoLink.clearWebhookChannel()

        return ProjectGithubRepoResDto.of(projectGithubRepoRepository.save(repoLink))
    }
}
