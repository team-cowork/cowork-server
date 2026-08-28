package com.cowork.project.domain.github.service.impl

import com.cowork.project.domain.github.entity.ProjectGithubRepo
import com.cowork.project.domain.github.event.ProjectGithubRepoEventPublisher
import com.cowork.project.domain.github.presentation.data.request.AddProjectGithubRepoReqDto
import com.cowork.project.domain.github.presentation.data.response.ProjectGithubRepoResDto
import com.cowork.project.domain.github.repository.ProjectGithubRepoRepository
import com.cowork.project.domain.github.service.AddProjectGithubRepoService
import com.cowork.project.domain.github.service.GithubRepoUrlParser
import com.cowork.project.domain.project.service.ProjectAccessGuard
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException

@Service
class AddProjectGithubRepoServiceImpl(
    private val projectGithubRepoRepository: ProjectGithubRepoRepository,
    private val projectAccessGuard: ProjectAccessGuard,
    private val repoEventPublisher: ProjectGithubRepoEventPublisher,
) : AddProjectGithubRepoService {

    @Transactional
    override fun execute(userId: Long, projectId: Long, request: AddProjectGithubRepoReqDto): ProjectGithubRepoResDto {
        val project = projectAccessGuard.findProjectForUpdateOrThrow(projectId)
        projectAccessGuard.requireProjectModifier(project, userId)

        val ref = GithubRepoUrlParser.parse(request.githubRepoUrl)
            ?: throw ExpectedException("유효하지 않은 GitHub 레포지토리 URL입니다.", HttpStatus.BAD_REQUEST)
        // owner/repo로 정규화한 canonical URL로 저장해야 중복 검사와 웹훅 대상 역조회(owner/repo 조합)가
        // URL 표기 방식(트레일링 슬래시, .git suffix 등) 차이에 흔들리지 않는다.
        val canonicalUrl = "https://github.com/${ref.owner}/${ref.repo}"

        if (projectGithubRepoRepository.existsByTeamIdAndGithubRepoUrl(project.teamId, canonicalUrl)) {
            throw ExpectedException("이미 팀 내 다른 프로젝트에 연결된 레포입니다.", HttpStatus.CONFLICT)
        }

        val repoLink = ProjectGithubRepo(projectId = projectId, teamId = project.teamId, githubRepoUrl = canonicalUrl)

        // 동시에 같은 팀에서 같은 레포를 두 번 등록하면 위 existsBy 검사만으로는 막지 못한다
        // (check-then-act가 원자적이지 않음). DB 유니크 제약(V8)이 최종 방어선이므로, 여기서 명시적으로
        // flush해 위반 시 500이 아니라 409로 매핑한다.
        val saved = try {
            projectGithubRepoRepository.saveAndFlush(repoLink)
        } catch (e: DataIntegrityViolationException) {
            throw ExpectedException("이미 팀 내 다른 프로젝트에 연결된 레포입니다.", HttpStatus.CONFLICT)
        }

        repoEventPublisher.publishUpsert(saved)
        return ProjectGithubRepoResDto.of(saved)
    }
}
