package com.cowork.project.domain

import com.cowork.global.entity.BaseTimeEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "tb_projects")
class Project(
    @Column(name = "team_id", nullable = false)
    val teamId: Long,

    @Column(nullable = false, length = 100)
    var name: String,

    @Column(length = 500)
    var description: String?,

    @Column(name = "github_repo_url", length = 512)
    var githubRepoUrl: String?,

    @Column(nullable = false)
    var position: Int,

    @Column(name = "created_by", nullable = false)
    val createdBy: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: ProjectStatus = ProjectStatus.ACTIVE,

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
) : BaseTimeEntity() {

    fun update(name: String?, description: String?, githubRepoUrl: String?, status: ProjectStatus?) {
        name?.let { this.name = it }
        description?.let { this.description = it.ifBlank { null } }
        githubRepoUrl?.let { this.githubRepoUrl = it.ifBlank { null } }
        status?.let { this.status = it }
    }

    fun moveTo(position: Int) {
        this.position = position
    }
}
