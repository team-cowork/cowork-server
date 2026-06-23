package com.cowork.project.domain.project.entity

import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.LocalDateTime

@Entity
@Table(name = "tb_projects")
class Project(

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "team_id", nullable = false)
    val teamId: Long,

    @Column(nullable = false, length = 100)
    var name: String,

    @Column(length = 500)
    var description: String?,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: ProjectStatus = ProjectStatus.ACTIVE,

    @Column(nullable = false)
    var position: Int = 0,

    @Column(name = "created_by", nullable = false)
    val createdBy: Long,

    @Column(name = "github_repo_url", length = 512)
    var githubRepoUrl: String? = null,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
) {
    fun updateName(newName: String) {
        name = newName
    }

    fun updateDescription(newDescription: String?) {
        description = newDescription
    }

    fun updateStatus(newStatus: ProjectStatus) {
        status = newStatus
    }

    fun updatePosition(position: Int) {
        this.position = position
    }

    fun linkGithubRepo(url: String) {
        githubRepoUrl = url
    }

    fun unlinkGithubRepo() {
        githubRepoUrl = null
    }
}
