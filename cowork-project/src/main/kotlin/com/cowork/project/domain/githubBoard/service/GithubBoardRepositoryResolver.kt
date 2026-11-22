package com.cowork.project.domain.githubBoard.service

import com.cowork.project.domain.github.repository.ProjectGithubRepoRepository
import com.cowork.project.domain.github.service.GithubRepoRef
import com.cowork.project.domain.github.service.GithubRepoUrlParser
import com.cowork.project.domain.githubBoard.entity.ProjectGithubBoard
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import team.themoment.sdk.exception.ExpectedException

/** 보드 수정 권한을 확인한 뒤 사용한다. 저장소 연결은 같은 프로젝트와 팀 조직에 속해야 한다. */
@Component
class GithubBoardRepositoryResolver(private val projectGithubRepoRepository: ProjectGithubRepoRepository) {
    fun resolve(board: ProjectGithubBoard, repoId: Long): GithubRepoRef {
        val link = projectGithubRepoRepository.findByIdAndProjectId(repoId, board.projectId)
            ?: throw ExpectedException("등록된 저장소를 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
        return requireOwner(board, link.githubRepoUrl)
    }

    fun resolveContent(board: ProjectGithubBoard, fullName: String?): GithubRepoRef {
        val ref = fullName?.let { GithubRepoUrlParser.parse("https://github.com/$it") }
            ?: throw ExpectedException("카드의 저장소를 확인할 수 없습니다.", HttpStatus.BAD_REQUEST)
        requireOwner(board, "https://github.com/${ref.owner}/${ref.repo}")
        val linked = projectGithubRepoRepository.findAllByProjectId(board.projectId).any { link ->
            GithubRepoUrlParser.parse(link.githubRepoUrl)?.let {
                it.owner.equals(ref.owner, ignoreCase = true) && it.repo.equals(ref.repo, ignoreCase = true)
            } == true
        }
        if (!linked) {
            throw ExpectedException("프로젝트에 연결된 저장소의 이슈만 수정할 수 있습니다.", HttpStatus.FORBIDDEN)
        }
        return ref
    }

    private fun requireOwner(board: ProjectGithubBoard, url: String): GithubRepoRef {
        val ref = GithubRepoUrlParser.parse(url)
            ?: throw ExpectedException("연결된 GitHub 저장소 URL이 올바르지 않습니다.", HttpStatus.BAD_REQUEST)
        if (!ref.owner.equals(board.ownerLogin, ignoreCase = true)) {
            throw ExpectedException("팀에 연결된 GitHub 조직의 저장소만 사용할 수 있습니다.", HttpStatus.FORBIDDEN)
        }
        return ref
    }
}
