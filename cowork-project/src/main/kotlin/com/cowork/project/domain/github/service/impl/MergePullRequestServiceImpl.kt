package com.cowork.project.domain.github.service.impl

import com.cowork.project.domain.github.event.GithubActionCommandPublisher
import com.cowork.project.domain.github.event.GithubPullRequestActionCommand
import com.cowork.project.domain.github.service.GithubRepoAccessResolver
import com.cowork.project.domain.github.service.GithubUsernameResolver
import com.cowork.project.domain.github.service.MergePullRequestService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class MergePullRequestServiceImpl(
    private val repoAccessResolver: GithubRepoAccessResolver,
    private val usernameResolver: GithubUsernameResolver,
    private val commandPublisher: GithubActionCommandPublisher,
) : MergePullRequestService {

    @Transactional(readOnly = true)
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
