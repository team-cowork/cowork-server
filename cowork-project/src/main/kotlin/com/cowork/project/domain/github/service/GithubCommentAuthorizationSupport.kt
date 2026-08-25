package com.cowork.project.domain.github.service

import com.cowork.project.domain.github.client.GithubAppClient
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import team.themoment.sdk.exception.ExpectedException

/**
 * 댓글은 로컬에 저장하지 않으므로, 수정/삭제 시점에 GitHub에서 댓글을 먼저 조회해
 * 작성자를 확인한다. 프로젝트 OWNER/ADMIN(수정 권한 보유자)이면 작성자 여부와
 * 무관하게 통과시키고, 그 외에는 댓글 작성자 본인만 통과시킨다.
 */
@Component
class GithubCommentAuthorizationSupport(
    private val repoAccessResolver: GithubRepoAccessResolver,
    private val usernameResolver: GithubUsernameResolver,
    private val githubAppClient: GithubAppClient,
    private val callExecutor: GithubAppCallExecutor,
) {

    fun authorize(userId: Long, projectId: Long, repoId: Long, commentId: Long): GithubRepoRef {
        val repo = repoAccessResolver.resolveForRead(userId, projectId, repoId)
        val isProjectModifier = runCatching { repoAccessResolver.resolveForModify(userId, projectId, repoId) }.isSuccess
        if (isProjectModifier) return repo

        val requesterGithubUsername = usernameResolver.resolve(userId)
        val comment = callExecutor.execute { githubAppClient.getIssueComment(repo.owner, repo.repo, commentId) }
        if (comment.author != requesterGithubUsername) {
            throw ExpectedException("본인이 작성한 댓글만 수정/삭제할 수 있습니다.", HttpStatus.FORBIDDEN)
        }
        return repo
    }
}
