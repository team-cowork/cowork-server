package com.cowork.project.domain.project.service.impl

import com.cowork.project.domain.channel.service.ChannelProjectionReader
import com.cowork.project.domain.github.event.ProjectGithubRepoEventPublisher
import com.cowork.project.domain.github.presentation.data.response.ProjectGithubRepoResDto
import com.cowork.project.domain.github.repository.ProjectGithubRepoRepository
import com.cowork.project.domain.project.presentation.data.request.SetProjectGithubWebhookChannelReqDto
import com.cowork.project.domain.project.service.ProjectAccessGuard
import com.cowork.project.domain.project.service.SetProjectGithubWebhookChannelService
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException

@Service
class SetProjectGithubWebhookChannelServiceImpl(
    private val projectGithubRepoRepository: ProjectGithubRepoRepository,
    private val projectAccessGuard: ProjectAccessGuard,
    private val channelProjectionReader: ChannelProjectionReader,
    private val repoEventPublisher: ProjectGithubRepoEventPublisher,
) : SetProjectGithubWebhookChannelService {

    @Transactional
    override fun execute(
        userId: Long,
        projectId: Long,
        repoId: Long,
        request: SetProjectGithubWebhookChannelReqDto,
    ): ProjectGithubRepoResDto {
        val project = projectAccessGuard.findProjectForUpdateOrThrow(projectId)
        projectAccessGuard.requireProjectModifier(project, userId)
        projectGithubRepoRepository.findByIdAndProjectId(repoId, projectId)
            ?: throw ExpectedException("등록된 레포를 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
        channelProjectionReader.requireProjectChannel(request.channelId, projectId)
        val repoLink = projectGithubRepoRepository.findByIdAndProjectIdForUpdate(repoId, projectId)
            ?: throw ExpectedException("등록된 레포를 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
        repoLink.setWebhookChannel(request.channelId)
        val saved = projectGithubRepoRepository.save(repoLink)
        repoEventPublisher.publishUpsert(saved)
        return ProjectGithubRepoResDto.of(saved)
    }
}
