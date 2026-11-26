package com.cowork.project.domain.github.presentation.data.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import java.util.UUID

data class GithubTimelineQueryReqDto(
    @param:Schema(minimum = "0", defaultValue = "0")
    @field:Min(0, message = "페이지 번호는 0 이상이어야 합니다.")
    val page: Int = 0,
    @param:Schema(minimum = "1", maximum = "100", defaultValue = "30")
    @field:Min(1, message = "페이지 크기는 1 이상이어야 합니다.")
    @field:Max(100, message = "페이지 크기는 100 이하여야 합니다.")
    val size: Int = 30,
    @param:Schema(description = "첫 페이지에서 받은 스냅샷 ID. 다음 페이지부터 필수입니다.")
    val snapshotId: UUID? = null,
)
