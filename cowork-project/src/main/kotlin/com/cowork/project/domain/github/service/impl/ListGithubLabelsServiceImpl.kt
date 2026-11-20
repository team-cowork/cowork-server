package com.cowork.project.domain.github.service.impl

import com.cowork.project.domain.github.presentation.data.response.GithubLabelResDto
import com.cowork.project.domain.github.service.GithubLabelReader
import com.cowork.project.domain.github.service.GithubRepoAccessResolver
import com.cowork.project.domain.github.service.ListGithubLabelsService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ListGithubLabelsServiceImpl(
    private val githubRepoAccessResolver: GithubRepoAccessResolver,
    private val githubLabelReader: GithubLabelReader,
) : ListGithubLabelsService {

    @Transactional(readOnly = true)
    override fun execute(userId: Long, projectId: Long, repoId: Long): List<GithubLabelResDto> {
        val repo = githubRepoAccessResolver.resolveForRead(userId, projectId, repoId)
        return githubLabelReader.listLabels(repo.owner, repo.repo)
    }
}
