package com.cowork.project.dto

data class CreateProjectRequest(
    val teamId: Long,
    val name: String,
    val description: String? = null,
)
