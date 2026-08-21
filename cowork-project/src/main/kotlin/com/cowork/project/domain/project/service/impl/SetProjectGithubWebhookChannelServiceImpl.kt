package com.cowork.project.domain.project.service.impl

import com.cowork.project.domain.github.presentation.data.response.ProjectGithubRepoResDto
import com.cowork.project.domain.github.repository.ProjectGithubRepoRepository
import com.cowork.project.domain.project.presentation.data.request.SetProjectGithubWebhookChannelReqDto
import com.cowork.project.domain.project.service.ProjectAccessGuard
import com.cowork.project.domain.project.service.SetProjectGithubWebhookChannelService
import com.cowork.project.global.client.ChannelClient
import feign.FeignException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import team.themoment.sdk.exception.ExpectedException

@Service
class SetProjectGithubWebhookChannelServiceImpl(
    private val projectGithubRepoRepository: ProjectGithubRepoRepository,
    private val projectAccessGuard: ProjectAccessGuard,
    private val channelClient: ChannelClient,
    private val transactionTemplate: TransactionTemplate,
) : SetProjectGithubWebhookChannelService {

    override fun execute(
        userId: Long,
        projectId: Long,
        repoId: Long,
        request: SetProjectGithubWebhookChannelReqDto,
    ): ProjectGithubRepoResDto {
        val project = projectAccessGuard.findProjectOrThrow(projectId)
        projectAccessGuard.requireProjectModifier(project, userId)
        val repoLink = projectGithubRepoRepository.findByIdAndProjectId(repoId, projectId)
            ?: throw ExpectedException("등록된 레포를 찾을 수 없습니다.", HttpStatus.NOT_FOUND)

        // Feign 원격 호출은 트랜잭션 밖에서 수행한다 (쓰기 트랜잭션이 원격 호출 지연 동안 열려있지 않도록).
        val channel = try {
            channelClient.getChannel(userId, request.channelId)
        } catch (e: FeignException.NotFound) {
            throw ExpectedException("존재하지 않는 채널입니다.", HttpStatus.NOT_FOUND)
        } catch (e: FeignException.Forbidden) {
            throw ExpectedException("채널에 접근할 권한이 없습니다.", HttpStatus.FORBIDDEN)
        } catch (e: FeignException) {
            throw ExpectedException("채널 서비스와 통신 중 오류가 발생했습니다.", HttpStatus.BAD_GATEWAY)
        }
        if (channel.projectId != projectId) {
            throw ExpectedException("이 프로젝트 소속 채널이 아닙니다.", HttpStatus.BAD_REQUEST)
        }

        return transactionTemplate.execute {
            repoLink.setWebhookChannel(request.channelId)
            ProjectGithubRepoResDto.of(projectGithubRepoRepository.save(repoLink))
        }!!
    }
}
