package com.cowork.project.domain.github.event

import com.cowork.project.domain.github.entity.ProjectGithubRepo
import com.cowork.project.domain.github.service.GithubRepoUrlParser
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.Locale

@Entity
@Table(name = "tb_project_github_repo_event_tombstones")
class ProjectGithubRepoEventTombstone(
    @Id
    @Column(name = "repo_id", nullable = false)
    val repoId: Long,

    @Column(name = "project_id", nullable = false)
    var projectId: Long,

    @Column(name = "team_id", nullable = false)
    var teamId: Long,

    @Column(name = "github_repo_url", nullable = false, length = 512)
    var githubRepoUrl: String,

    @Column(nullable = false, length = 255)
    var owner: String,

    @Column(nullable = false, length = 255)
    var repo: String,

    @Column(name = "github_webhook_channel_id")
    var githubWebhookChannelId: Long?,

    @Column(name = "state_occurred_at", nullable = false)
    var stateOccurredAt: Instant,
) {
    fun replaceFrom(repoLink: ProjectGithubRepo, version: Instant) {
        val ref = requireNotNull(GithubRepoUrlParser.parse(repoLink.githubRepoUrl)) {
            "Canonical GitHub repository URL is invalid: ${repoLink.githubRepoUrl}"
        }
        projectId = repoLink.projectId
        teamId = repoLink.teamId
        githubRepoUrl = repoLink.githubRepoUrl
        owner = ref.owner.lowercase(Locale.ROOT)
        repo = ref.repo.lowercase(Locale.ROOT)
        githubWebhookChannelId = repoLink.githubWebhookChannelId
        stateOccurredAt = version
    }

    companion object {
        fun from(repoLink: ProjectGithubRepo, version: Instant): ProjectGithubRepoEventTombstone {
            val ref = requireNotNull(GithubRepoUrlParser.parse(repoLink.githubRepoUrl)) {
                "Canonical GitHub repository URL is invalid: ${repoLink.githubRepoUrl}"
            }
            return ProjectGithubRepoEventTombstone(
                repoId = repoLink.id,
                projectId = repoLink.projectId,
                teamId = repoLink.teamId,
                githubRepoUrl = repoLink.githubRepoUrl,
                owner = ref.owner.lowercase(Locale.ROOT),
                repo = ref.repo.lowercase(Locale.ROOT),
                githubWebhookChannelId = repoLink.githubWebhookChannelId,
                stateOccurredAt = version,
            )
        }
    }
}
