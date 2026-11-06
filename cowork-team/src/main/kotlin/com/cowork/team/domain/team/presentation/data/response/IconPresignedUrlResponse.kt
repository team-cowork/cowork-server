package com.cowork.team.domain.team.presentation.data.response

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "팀 아이콘 Presigned URL 발급 응답")
data class IconPresignedUrlResponse(
    @field:Schema(description = "파일을 PUT으로 업로드할 URL") val uploadUrl: String,
    @field:Schema(description = "업로드 확인 요청에 사용할 객체 키") val objectKey: String,
)
