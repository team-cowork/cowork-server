package com.cowork.project.domain.project.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "tb_project_access_projections")
class ProjectAccessProjection(
    @Id val accessKey: String,
    val projectId: Long,
    val userId: Long,
    var canRead: Boolean,
    var canWrite: Boolean,
    @Column(nullable = false) var sourceOccurredAt: Instant,
    var canReorder: Boolean = false,
    var canManage: Boolean = false,
    @Column(nullable = false) var sourceMembershipVersion: Instant = Instant.EPOCH,
    @Column(name = "source_authorization_version") var sourceAuthorizationVersion: Instant? = null,
)
