package com.cowork.preference.domain.access.effective.presentation

import com.cowork.preference.domain.access.effective.model.EffectivePermissionQuery
import com.cowork.preference.domain.access.effective.model.PermissionScope
import com.cowork.preference.domain.access.effective.service.EffectivePermissionService
import com.cowork.preference.domain.access.presentation.auditAuthorizationFailure
import com.cowork.preference.domain.access.presentation.authorizationStatus
import com.cowork.preference.global.coroutine.runCatchingCancellable
import com.cowork.preference.global.logging.logger
import com.cowork.preference.global.security.RequesterContext
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class EffectivePermissionHandler(
    private val effectivePermissionService: EffectivePermissionService,
    private val scope: CoroutineScope,
) {
    fun routes(router: Router) {
        router.get("/preferences/permissions/me").handler(::getMyPermissions)
    }

    private fun getMyPermissions(ctx: RoutingContext) {
        val requester = RequesterContext.from(ctx)
        if (requester == null) {
            respondError(ctx, 401, "인증 정보가 필요합니다.")
            return
        }
        val query = runCatching { EffectivePermissionQuery.parse { ctx.queryParams().get(it) } }.getOrElse {
            respondError(ctx, 400, it.message)
            return
        }
        scope.launch(ctx.vertx().dispatcher()) {
            runCatchingCancellable { effectivePermissionService.evaluate(requester, query) }
                .onSuccess {
                    ctx.response().setStatusCode(200).putHeader("Content-Type", "application/json")
                        .end(it.toJson().encode())
                }
                .onFailure { error -> respondFailure(ctx, error, requester, query.scope) }
        }
    }

    private fun respondFailure(
        ctx: RoutingContext,
        error: Throwable,
        requester: RequesterContext,
        scope: PermissionScope,
    ) {
        val authorizationStatus = authorizationStatus(error)
        if (authorizationStatus != null) {
            val (resource, resourceId) = scope.resource()
            auditAuthorizationFailure(logger(), authorizationStatus, requester.userId, resource, resourceId)
        }
        val status = authorizationStatus ?: if (error is IllegalArgumentException) 400 else 500
        if (status == 500) logger().error("Failed to evaluate effective permissions", error)
        respondError(ctx, status, if (status == 500) "권한을 조회할 수 없습니다." else error.message)
    }

    private fun respondError(ctx: RoutingContext, status: Int, message: String?) {
        ctx.response().setStatusCode(status).putHeader("Content-Type", "application/json")
            .end(JsonObject().put("error", message ?: "권한을 조회할 수 없습니다.").encode())
    }

    private fun PermissionScope.resource(): Pair<String, Long> = when (this) {
        PermissionScope.Account -> "EFFECTIVE_PERMISSION_ACCOUNT" to 0L
        is PermissionScope.Team -> "EFFECTIVE_PERMISSION_TEAM" to teamId
        is PermissionScope.Channel -> "EFFECTIVE_PERMISSION_CHANNEL" to channelId
        is PermissionScope.Project -> "EFFECTIVE_PERMISSION_PROJECT" to projectId
    }
}
