package com.cowork.preference.domain.access.effective.model

import com.cowork.preference.domain.access.permission.PermissionKeys
import com.cowork.preference.domain.accountrole.AccountRolePolicy

/** 요청자 본인의 유효 권한 조회 조건. */
data class EffectivePermissionQuery(
    val scope: PermissionScope,
    /** 비어 있으면 범위의 모든 권한을 반환한다. */
    val permissions: Set<String>,
    val explain: Boolean,
) {
    /** 반환할 권한 키. 범위의 정의 순서를 따른다. */
    val keys: List<String>
        get() = scope.keys.filter { permissions.isEmpty() || it in permissions }

    companion object {
        /** 쿼리 파라미터를 검증한다. 잘못된 값이면 [IllegalArgumentException]을 던진다. */
        fun parse(param: (String) -> String?): EffectivePermissionQuery {
            val teamId = positiveId(param("teamId"))
            val channelId = positiveId(param("channelId"))
            val projectId = positiveId(param("projectId"))
            require(listOfNotNull(teamId, channelId, projectId).size <= 1) {
                "teamId, channelId, projectId 중 하나만 지정할 수 있습니다."
            }
            val scope = when {
                teamId != null -> PermissionScope.Team(teamId)
                channelId != null -> PermissionScope.Channel(channelId)
                projectId != null -> PermissionScope.Project(projectId)
                else -> PermissionScope.Account
            }
            val permissions = param("permissions")
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.toSet()
                .orEmpty()
            require(scope.keys.containsAll(permissions)) { "조회 범위에서 지원하지 않는 권한입니다." }
            val explain = when (param("explain")) {
                null, "false" -> false
                "true" -> true
                else -> throw IllegalArgumentException("explain은 true 또는 false여야 합니다.")
            }
            return EffectivePermissionQuery(scope, permissions, explain)
        }

        private fun positiveId(value: String?): Long? = value?.let {
            requireNotNull(it.toLongOrNull()?.takeIf { id -> id > 0 }) { "ID는 양수여야 합니다." }
        }
    }
}

sealed interface PermissionScope {
    val keys: List<String>

    data object Account : PermissionScope {
        override val keys: List<String> = AccountRolePolicy.actions.sorted()
    }

    data class Team(val teamId: Long) : PermissionScope {
        override val keys: List<String> get() = PermissionKeys.TEAM_SCOPED.toList()
    }

    data class Channel(val channelId: Long) : PermissionScope {
        override val keys: List<String> get() = PermissionKeys.CHANNEL_SCOPED.toList()
    }

    data class Project(val projectId: Long) : PermissionScope {
        override val keys: List<String> get() = PermissionKeys.PROJECT_SCOPED.toList()
    }
}
