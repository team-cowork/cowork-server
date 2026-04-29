package com.cowork.project.dto

data class UpdateProjectRequest(
    val name: String? = null,
    val description: String? = null,
    val status: String? = null,
)
