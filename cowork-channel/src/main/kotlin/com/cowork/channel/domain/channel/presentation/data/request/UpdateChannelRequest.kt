package com.cowork.channel.domain.channel.presentation.data.request

import io.swagger.v3.oas.annotations.media.Schema

data class UpdateChannelRequest(
    @param:Schema(
        description = "채널 이름. 생략하거나 null이면 기존 이름을 유지합니다. 앞뒤 공백을 제거한 뒤 최대 100자이며, 공백이나 보이지 않는 문자만으로 구성할 수 없습니다.",
    )
    @field:ValidChannelName
    var name: String? = null,
    val description: String? = null,
    val isPrivate: Boolean? = null,
    val projectId: Long? = null,
) {
    init {
        name = name?.trim()
    }
}
