package com.cowork.preference.domain.access.effective.model

import com.cowork.preference.domain.access.permission.PermissionDecision
import io.vertx.core.json.JsonObject
import java.time.Instant

data class EffectivePermissionResult(
    val accountId: Long,
    val scope: PermissionScope,
    /** 채널과 프로젝트 범위에서는 리소스가 속한 팀이다. */
    val teamId: Long?,
    val evaluatedAt: Instant,
    val decisions: Map<String, PermissionDecision>,
    val explain: Boolean,
) {
    fun toJson(): JsonObject {
        val json = JsonObject()
            .put("accountId", accountId)
            .put("scope", scopeJson())
            .put("evaluatedAt", evaluatedAt.toString())
            .put("permissions", JsonObject(decisions.mapValues { it.value.allowed }))
        if (explain) json.put("explanations", JsonObject(decisions.mapValues { it.value.toJson() }))
        return json
    }

    private fun scopeJson(): JsonObject = when (scope) {
        PermissionScope.Account -> JsonObject().put("type", "ACCOUNT")

        is PermissionScope.Team -> JsonObject().put("type", "TEAM").put("teamId", scope.teamId)

        is PermissionScope.Channel ->
            JsonObject().put("type", "CHANNEL").put("teamId", teamId).put("channelId", scope.channelId)

        is PermissionScope.Project ->
            JsonObject().put("type", "PROJECT").put("teamId", teamId).put("projectId", scope.projectId)
    }
}
