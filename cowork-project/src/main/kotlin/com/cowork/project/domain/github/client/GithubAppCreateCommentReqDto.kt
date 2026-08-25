package com.cowork.project.domain.github.client

data class GithubAppCreateCommentReqDto(
    val body: String,
    val requesterGithubUsername: String,
)
