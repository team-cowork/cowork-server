package com.cowork.channel.domain.channel.presentation.data.request

import io.swagger.v3.oas.annotations.media.Schema

data class UpdateChannelRequest(
    @param:Schema(description = "채널 이름. 생략하거나 null이면 기존 이름을 유지합니다. 공백만으로 구성할 수 없고 최대 100자이며, 앞뒤 공백을 포함해 입력값 그대로 저장합니다.")
    val name: String? = null,
    val description: String? = null,
    val isPrivate: Boolean? = null,
    val projectId: Long? = null,
)
