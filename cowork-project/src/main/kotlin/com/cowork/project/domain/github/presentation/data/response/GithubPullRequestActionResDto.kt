package com.cowork.project.domain.github.presentation.data.response

import com.cowork.project.domain.github.client.GithubPullRequestActionResult
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "GitHub PR 머지·승인·닫기 결과")
data class GithubPullRequestActionResDto(
    @field:Schema(description = "PR 번호", example = "1")
    val prNumber: Int,
    @field:Schema(description = "GitHub PR URL", example = "https://github.com/my-org/my-repo/pull/1")
    val prUrl: String,
) {
    companion object {
        fun of(result: GithubPullRequestActionResult) = GithubPullRequestActionResDto(
            prNumber = result.prNumber,
            prUrl = result.prUrl,
        )
    }
}
