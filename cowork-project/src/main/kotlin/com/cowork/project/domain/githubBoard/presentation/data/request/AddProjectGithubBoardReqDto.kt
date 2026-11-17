package com.cowork.project.domain.githubBoard.presentation.data.request

import io.swagger.v3.oas.annotations.media.Schema

data class AddProjectGithubBoardReqDto(
    @param:Schema(
        description = "연결할 조직 소유 GitHub Project URL",
        example = "https://github.com/orgs/my-org/projects/3",
        required = true,
    )
    val url: String,
)
