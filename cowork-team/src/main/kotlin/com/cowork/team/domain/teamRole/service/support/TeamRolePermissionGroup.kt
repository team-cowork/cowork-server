package com.cowork.team.domain.teamRole.service.support

/** 역할 설정 화면에서 권한을 묶어 보여주는 그룹. 선언 순서가 화면 표시 순서다. */
enum class TeamRolePermissionGroup(val title: String) {
    TEAM("팀 관리"),
    MEMBER("멤버 관리"),
    CHANNEL("채널 관리"),
    TEXT_CHANNEL("텍스트 채널"),
    VOICE_CHANNEL("음성 채널"),
    PROJECT("프로젝트"),
}
