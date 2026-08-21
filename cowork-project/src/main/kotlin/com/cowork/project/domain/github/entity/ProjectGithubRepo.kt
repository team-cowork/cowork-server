package com.cowork.project.domain.github.entity

import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.LocalDateTime

@Entity
@Table(name = "tb_project_github_repos")
class ProjectGithubRepo(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "project_id", nullable = false)
    val projectId: Long,

    @Column(name = "team_id", nullable = false)
    val teamId: Long,

    @Column(name = "github_repo_url", nullable = false, length = 512)
    val githubRepoUrl: String,

    @Column(name = "github_webhook_channel_id")
    var githubWebhookChannelId: Long? = null,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
) {
    fun setWebhookChannel(channelId: Long) {
        githubWebhookChannelId = channelId
    }

    fun clearWebhookChannel() {
        githubWebhookChannelId = null
    }
}
