package com.cowork.project.domain.githubBoard.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.LocalDateTime

/** cowork 프로젝트에 연결한 조직 소유 GitHub Project. `ownerLogin`은 소문자로 저장한다. */
@Entity
@Table(name = "tb_project_github_boards")
class ProjectGithubBoard(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "project_id", nullable = false)
    val projectId: Long,

    @Column(name = "team_id", nullable = false)
    val teamId: Long,

    @Column(name = "owner_login", nullable = false, length = 100)
    var ownerLogin: String,

    @Column(name = "project_number", nullable = false)
    val projectNumber: Int,

    @Column(name = "created_by", nullable = false)
    val createdBy: Long,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
) {
    val projectionId: String get() = GithubProjectProjection.id(ownerLogin, projectNumber)

    val url: String get() = "https://github.com/orgs/$ownerLogin/projects/$projectNumber"
}
