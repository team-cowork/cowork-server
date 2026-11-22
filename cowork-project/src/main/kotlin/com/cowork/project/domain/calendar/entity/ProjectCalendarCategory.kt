package com.cowork.project.domain.calendar.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "tb_project_calendar_categories")
class ProjectCalendarCategory(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "project_id", nullable = false)
    val projectId: Long,

    @Column(name = "team_id", nullable = false)
    val teamId: Long,

    @Column(nullable = false, length = 50)
    var name: String,

    @Column(name = "color_hex", nullable = false, length = 7)
    var colorHex: String,

    @Column(name = "created_by", nullable = false)
    val createdBy: Long,
)
