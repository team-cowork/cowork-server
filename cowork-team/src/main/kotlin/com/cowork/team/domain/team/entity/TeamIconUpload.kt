package com.cowork.team.domain.team.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "tb_team_icon_uploads")
class TeamIconUpload(
    @Id
    @Column(name = "object_key", length = 255)
    val objectKey: String,
    @Column(name = "upload_key", nullable = false, length = 255, unique = true)
    val uploadKey: String,
    @Column(name = "uploaded_by", nullable = false)
    val uploadedBy: Long,
    @Column(name = "content_type", nullable = false, length = 100)
    val contentType: String,
    @Column(nullable = false)
    var confirmed: Boolean = false,
    @Column(name = "team_id")
    var teamId: Long? = null,
    @Column(nullable = false)
    var retired: Boolean = false,
)
