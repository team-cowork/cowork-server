package com.cowork.channel.domain.channel.presentation.data.response

import io.swagger.v3.oas.annotations.media.Schema

data class ArchivedChannelPageResponse(
    @field:Schema(description = "읽을 수 있는 보관 채널. 권한 필터 때문에 요청한 크기보다 적을 수 있습니다.")
    val channels: List<ChannelResponse>,
    @field:Schema(description = "다음 페이지 커서. 더 조회할 채널이 없으면 null입니다.")
    val nextCursor: String?,
)
