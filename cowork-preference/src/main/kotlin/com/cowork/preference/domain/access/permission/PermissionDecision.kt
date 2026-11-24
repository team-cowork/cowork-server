package com.cowork.preference.domain.access.permission

import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject

/** 권한 하나의 판정 결과와 판정에 쓰인 정책 값. 실제 인가 판정과 같은 resolver가 만든다. */
data class PermissionDecision(
    val allowed: Boolean,
    val decidedBy: PermissionDecisionReason,
    val sources: List<PermissionSource>,
) {
    fun toJson(): JsonObject = JsonObject()
        .put("allowed", allowed)
        .put("decidedBy", decidedBy.name)
        .put("sources", JsonArray(sources.map { it.toJson() }))
}

/** 최종 판정을 정한 규칙. */
enum class PermissionDecisionReason {
    /** administrator 권한이 있는 역할이 허용했다. */
    ADMINISTRATOR,

    /** 역할 하나 이상이 권한을 직접 허용했다. */
    ROLE_GRANT,

    /** 권한을 허용한 역할이 없다. */
    NO_GRANT,

    /** 비공개 채널 접근은 채널 덮어쓰기의 명시 허용이 필요하다. */
    PRIVATE_CHANNEL,

    /** 채널의 기본 역할 덮어쓰기가 결과를 정했다. */
    DEFAULT_OVERWRITE,

    /** 채널의 사용자 역할 덮어쓰기가 거부했다. */
    ROLE_OVERWRITE_DENY,

    /** 채널의 사용자 역할 덮어쓰기가 허용했다. 거부보다 우선한다. */
    ROLE_OVERWRITE_ALLOW,

    /** 채널 접근 권한이 없어 함께 거부되었다. 텍스트 채널은 message_read, 음성 채널은 voice_connect가 접근 권한이다. */
    REQUIRES_CHANNEL_ACCESS,

    /** 프로젝트 역할이 허용했다. 팀 역할보다 우선한다. */
    PROJECT_OVERRIDE_ALLOW,

    /** 프로젝트 역할이 거부했다. 팀 역할보다 우선한다. */
    PROJECT_OVERRIDE_DENY,

    /** 비공개 프로젝트 읽기는 프로젝트 역할의 명시 허용이 필요하다. */
    PRIVATE_PROJECT,

    /** 프로젝트 읽기 권한이 없어 함께 거부되었다. */
    REQUIRES_PROJECT_READ,

    /** 보드 보기 권한이 없어 함께 거부되었다. */
    REQUIRES_BOARD_READ,

    /** 저장된 프로젝트 접근 상태가 아직 최신 멤버십을 반영하지 않았다. 판정은 저장된 상태를 따른다. */
    ACCESS_STATE_PENDING,
}

/** 판정에 쓰인 정책 값 하나. [value]가 null이면 해당 정책에 값이 없다. */
data class PermissionSource(
    val kind: PermissionSourceKind,
    val permission: String,
    val value: Boolean?,
    val roleId: Long? = null,
    val roleName: String? = null,
) {
    fun toJson(): JsonObject = JsonObject()
        .put("kind", kind.name)
        .put("roleId", roleId)
        .put("roleName", roleName)
        .put("permission", permission)
        .put("value", value)
}

enum class PermissionSourceKind {
    ACCOUNT_ROLE,
    TEAM_ROLE,
    CHANNEL_DEFAULT_OVERWRITE,
    CHANNEL_ROLE_OVERWRITE,
    PROJECT_ROLE,
}

/** 판정 근거에 표시할 역할. */
data class PolicyRole(
    val roleId: Long?,
    val roleName: String?,
    val permissions: Map<String, Boolean>,
    val isDefault: Boolean = false,
)
