package com.cowork.project.domain.github.service.impl

import com.cowork.project.domain.github.event.GithubActionCommandPublisher
import com.cowork.project.domain.github.event.GithubPullRequestActionCommand
import com.cowork.project.domain.github.service.GithubRepoAccessResolver
import com.cowork.project.domain.github.service.GithubUsernameResolver
import com.cowork.project.domain.github.service.MergePullRequestService
import org.springframework.stereotype.Service

@Service
class MergePullRequestServiceImpl(
    private val repoAccessResolver: GithubRepoAccessResolver,
    private val usernameResolver: GithubUsernameResolver,
    private val commandPublisher: GithubActionCommandPublisher,
) : MergePullRequestService {

    // 브로커 적재 확인(최대 5초)을 기다리는 동안 DB 커넥션을 잡지 않도록 서비스 트랜잭션을 두지 않는다.
    override fun execute(userId: Long, projectId: Long, repoId: Long, prNumber: Int) {
        val repo = repoAccessResolver.resolveForModify(userId, projectId, repoId)
        val githubUsername = usernameResolver.resolve(userId)
        commandPublisher.publishPullRequestMerge(
            GithubPullRequestActionCommand(
                owner = repo.owner,
                repo = repo.repo,
                prNumber = prNumber,
                requesterGithubUsername = githubUsername,
            ),
        )
    }
}
