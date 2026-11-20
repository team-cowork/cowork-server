package com.cowork.project.domain.githubBoard.presentation.data.response

import com.cowork.project.domain.githubBoard.event.GithubProjectField
import io.swagger.v3.oas.annotations.media.Schema

data class GithubBoardFieldResDto(
    @field:Schema(description = "field ID", example = "PVTSSF_lADOBk9Ru84AqHDWzgg")
    val id: String,
    @field:Schema(description = "field 이름", example = "Status")
    val name: String,
    @field:Schema(description = "GitHub field 종류", example = "SINGLE_SELECT")
    val dataType: String,
) {
    companion object {
        fun of(field: GithubProjectField) = GithubBoardFieldResDto(field.id, field.name, field.dataType)
    }
}
