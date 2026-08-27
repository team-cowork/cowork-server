package com.cowork.project.domain.github.service.impl

import com.cowork.project.domain.github.client.GithubAppClient
import com.cowork.project.domain.github.client.GithubAppCreateCommentReqDto
import com.cowork.project.domain.github.event.GithubCommentNotificationPublisher
import com.cowork.project.domain.github.presentation.data.request.CreateGithubCommentReqDto
import com.cowork.project.domain.github.presentation.data.response.GithubCommentResDto
import com.cowork.project.domain.github.service.CreateGithubCommentService
import com.cowork.project.domain.github.service.GithubAppCallExecutor
import com.cowork.project.domain.github.service.GithubCommentParentType
import com.cowork.project.domain.github.service.GithubRepoAccessResolver
import com.cowork.project.domain.github.service.GithubRepoRef
import com.cowork.project.domain.github.service.GithubUsernameResolver
import com.cowork.project.domain.user.service.UserProfileProjectionReader
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class CreateGithubCommentServiceImpl(
    private val repoAccessResolver: GithubRepoAccessResolver,
    private val usernameResolver: GithubUsernameResolver,
    private val callExecutor: GithubAppCallExecutor,
    private val githubAppClient: GithubAppClient,
    private val profileReader: UserProfileProjectionReader,
    private val notificationPublisher: GithubCommentNotificationPublisher,
) : CreateGithubCommentService {
    private val logger = LoggerFactory.getLogger(CreateGithubCommentServiceImpl::class.java)

    @Transactional(readOnly = true)
    override fun execute(
        userId: Long,
        projectId: Long,
        repoId: Long,
        parentType: GithubCommentParentType,
        number: Int,
        request: CreateGithubCommentReqDto,
    ): GithubCommentResDto {
        val repo = repoAccessResolver.resolveForRead(userId, projectId, repoId)
        val requesterGithubUsername = usernameResolver.resolve(userId)

        val comment = callExecutor.execute {
            githubAppClient.createIssueComment(
                repo.owner,
                repo.repo,
                number,
                GithubAppCreateCommentReqDto(body = request.body, requesterGithubUsername = requesterGithubUsername),
            )
        }

        notifyParentAuthor(repo, parentType, number, comment)
        return comment
    }

    private fun notifyParentAuthor(
        repo: GithubRepoRef,
        parentType: GithubCommentParentType,
        number: Int,
        comment: GithubCommentResDto,
    ) {
        runCatching {
            val parentAuthorGithubUsername = when (parentType) {
                GithubCommentParentType.ISSUE ->
                    callExecutor.execute { githubAppClient.getIssue(repo.owner, repo.repo, number) }.author
                GithubCommentParentType.PULL_REQUEST ->
                    callExecutor.execute { githubAppClient.getPullRequest(repo.owner, repo.repo, number) }.author
            }
            if (parentAuthorGithubUsername == comment.author) return

            val targetUserId = profileReader.resolveUniqueUserId(parentAuthorGithubUsername)
            notificationPublisher.publishCommentCreated(
                targetUserId = targetUserId,
                data = mapOf(
                    "repo" to "${repo.owner}/${repo.repo}",
                    "number" to number,
                    "parentType" to parentType.name,
                    "commentAuthor" to comment.author,
                    "body" to comment.body,
                    "htmlUrl" to comment.htmlUrl,
                ),
            )
        }.onFailure { ex ->
            logger.warn("댓글 생성 알림 처리 실패 (댓글 생성 자체는 성공) [repo={}/{}, number={}]", repo.owner, repo.repo, number, ex)
        }
    }
}
