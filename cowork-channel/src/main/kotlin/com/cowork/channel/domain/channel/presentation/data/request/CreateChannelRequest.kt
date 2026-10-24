package com.cowork.channel.domain.channel.presentation.data.request

import io.swagger.v3.oas.annotations.media.Schema

data class CreateChannelRequest(
    val teamId: Long,
    val name: String,
    val type: String,
    val viewType: String,
    val description: String? = null,
    val isPrivate: Boolean = false,

    @param:Schema(description = "생성 시 적용할 역할별 초기 권한입니다. 일반 멤버의 비공개 채널은 생성자에게 접근을 허용하는 역할 정책이 필요합니다.")
    val initialRoleOverwrites: List<InitialChannelRoleOverwriteRequest> = emptyList(),
)

data class InitialChannelRoleOverwriteRequest(
    @param:Schema(description = "같은 팀에 속한 활성 역할 ID입니다. 같은 역할을 중복 지정할 수 없습니다.")
    val roleId: Long,

    @param:Schema(description = "true는 명시적 허용, false는 명시적 거부이며 누락한 키는 기본 권한을 따릅니다. 생성자의 팀 권한 범위 안에서만 지정할 수 있습니다.")
    val permissions: Map<String, Any?>,
)
