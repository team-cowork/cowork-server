package com.cowork.project.domain.project.service.impl

import com.cowork.project.domain.project.presentation.data.request.SetProjectGithubWebhookChannelReqDto
import com.cowork.project.domain.project.presentation.data.response.ProjectDetailResDto
import com.cowork.project.domain.project.service.ProjectAccessGuard
import com.cowork.project.domain.project.service.SetProjectGithubWebhookChannelService
import com.cowork.project.domain.projectMember.repository.ProjectMemberRepository
import com.cowork.project.global.client.ChannelClient
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException

@Service
class SetProjectGithubWebhookChannelServiceImpl(
    private val projectMemberRepository: ProjectMemberRepository,
    private val projectAccessGuard: ProjectAccessGuard,
    private val channelClient: ChannelClient,
) : SetProjectGithubWebhookChannelService {

    @Transactional
    override fun execute(
        userId: Long,
        projectId: Long,
        request: SetProjectGithubWebhookChannelReqDto,
    ): ProjectDetailResDto {
        val project = projectAccessGuard.findProjectOrThrow(projectId)
        projectAccessGuard.requireProjectModifier(project, userId)

        val channel = channelClient.getChannel(userId, request.channelId)
        if (channel.projectId != project.id) {
            throw ExpectedException("이 프로젝트 소속 채널이 아닙니다.", HttpStatus.BAD_REQUEST)
        }

        project.setGithubWebhookChannel(request.channelId)

        val memberCount = projectMemberRepository.countByProjectId(projectId)
        return ProjectDetailResDto.of(project, memberCount)
    }
}
