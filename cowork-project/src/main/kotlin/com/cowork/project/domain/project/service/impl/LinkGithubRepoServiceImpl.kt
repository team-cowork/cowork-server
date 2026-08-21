package com.cowork.project.domain.project.service.impl

import com.cowork.project.domain.github.presentation.data.request.LinkGithubRepoReqDto
import com.cowork.project.domain.github.service.GithubRepoUrlParser
import com.cowork.project.domain.project.presentation.data.response.ProjectDetailResDto
import com.cowork.project.domain.project.repository.ProjectRepository
import com.cowork.project.domain.project.service.LinkGithubRepoService
import com.cowork.project.domain.project.service.ProjectAccessGuard
import com.cowork.project.domain.projectMember.repository.ProjectMemberRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException

@Service
class LinkGithubRepoServiceImpl(
    private val projectRepository: ProjectRepository,
    private val projectMemberRepository: ProjectMemberRepository,
    private val projectAccessGuard: ProjectAccessGuard,
) : LinkGithubRepoService {

    @Transactional
    override fun execute(userId: Long, projectId: Long, request: LinkGithubRepoReqDto): ProjectDetailResDto {
        val project = projectAccessGuard.findProjectOrThrow(projectId)
        projectAccessGuard.requireProjectModifier(project, userId)

        val ref = GithubRepoUrlParser.parse(request.githubRepoUrl)
            ?: throw ExpectedException("유효하지 않은 GitHub 레포지토리 URL입니다.", HttpStatus.BAD_REQUEST)
        // owner/repo로 정규화한 canonical URL로 저장해야 중복 검사와 웹훅 대상 역조회(owner/repo 조합)가
        // URL 표기 방식(트레일링 슬래시, .git suffix, 대소문자 등) 차이에 흔들리지 않는다.
        val canonicalUrl = "https://github.com/${ref.owner}/${ref.repo}"

        if (projectRepository.existsByTeamIdAndGithubRepoUrlAndIdNot(project.teamId, canonicalUrl, project.id)) {
            throw ExpectedException("이미 팀 내 다른 프로젝트에 연결된 레포입니다.", HttpStatus.CONFLICT)
        }

        project.linkGithubRepo(canonicalUrl)

        val memberCount = projectMemberRepository.countByProjectId(projectId)
        return ProjectDetailResDto.of(project, memberCount)
    }
}
