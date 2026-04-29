package com.cowork.project.dto

data class AddProjectMemberRequest(
    val userId: Long,
    val role: String,
)
