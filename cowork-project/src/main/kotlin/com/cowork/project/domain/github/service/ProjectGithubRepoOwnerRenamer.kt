package com.cowork.project.domain.github.service

import com.cowork.project.domain.github.event.ProjectGithubRepoEventPublisher
import com.cowork.project.domain.github.repository.ProjectGithubRepoRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.Locale

/**
 * GitHub 조직 이름이 바뀌면 옛 owner로 저장된 팀의 레포 링크 URL을 새 owner로 바꾼다.
 * 그대로 두면 [GithubRepoAccessResolver.requireTeamOrgRepo]가 모든 링크를 403으로 막는다.
 */
@Component
class ProjectGithubRepoOwnerRenamer(
    private val repoLinkRepository: ProjectGithubRepoRepository,
    private val repoEventPublisher: ProjectGithubRepoEventPublisher,
) {
    private val log = LoggerFactory.getLogger(ProjectGithubRepoOwnerRenamer::class.java)

    @Transactional(propagation = Propagation.MANDATORY)
    fun rename(teamId: Long, previousOwner: String, newOwner: String) {
        val repoLinks = repoLinkRepository.findAllByTeamIdForUpdate(teamId)
        // 유니크 제약 (team_id, github_repo_url)은 대소문자를 구분하지 않으므로 소문자로 비교한다.
        val takenUrls = repoLinks.mapTo(HashSet()) { it.githubRepoUrl.lowercase(Locale.ROOT) }
        val occurredAt = Instant.now()
        repoLinks.forEach { repoLink ->
            val renamedUrl = renamedUrl(repoLink.githubRepoUrl, previousOwner, newOwner) ?: return@forEach
            if (!takenUrls.add(renamedUrl.lowercase(Locale.ROOT))) {
                log.warn(
                    "새 조직 이름의 레포 링크가 이미 있어 옛 링크를 그대로 둡니다 [teamId={}, repoId={}, url={}]",
                    teamId,
                    repoLink.id,
                    repoLink.githubRepoUrl,
                )
                return@forEach
            }
            repoLink.githubRepoUrl = renamedUrl
            repoLinkRepository.save(repoLink)
            repoEventPublisher.publishUpsert(repoLink, occurredAt)
        }
    }

    /** owner가 [previousOwner]인 URL만 새 owner의 canonical URL로 바꾼다. 바꿀 필요가 없으면 null. */
    private fun renamedUrl(githubRepoUrl: String, previousOwner: String, newOwner: String): String? {
        // 대소문자만 바뀐 이름은 권한 검사와 이벤트 owner가 모두 대소문자를 무시하므로 그대로 동작한다.
        if (previousOwner.equals(newOwner, ignoreCase = true)) return null
        val ref = GithubRepoUrlParser.parse(githubRepoUrl) ?: return null
        if (!ref.owner.equals(previousOwner, ignoreCase = true)) return null
        return "https://github.com/$newOwner/${ref.repo}"
    }
}
