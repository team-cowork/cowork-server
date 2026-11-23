package com.cowork.preference.domain.projectrole.presentation

import com.cowork.preference.domain.access.presentation.auditAuthorizationFailure
import com.cowork.preference.domain.access.presentation.authorizationStatus
import com.cowork.preference.domain.access.service.ProjectAccessGuard
import com.cowork.preference.domain.projectrole.service.ProjectRoleService
import com.cowork.preference.global.coroutine.runCatchingCancellable
import com.cowork.preference.global.logging.logger
import com.cowork.preference.global.security.RequesterContext
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class ProjectRoleHandler(
    private val service: ProjectRoleService,
    private val scope: CoroutineScope,
    private val projectAccessGuard: ProjectAccessGuard,
) {

    fun getRoles(ctx: RoutingContext) {
        val projectId = ctx.pathParam("projectId")?.toLongOrNull() ?: run {
            ctx.response().setStatusCode(400).end(errorBody("Invalid projectId"))
            return
        }
        val requester = requesterOrReject(ctx) ?: return
        scope.launch(ctx.vertx().dispatcher()) {
            if (!authorizeMember(ctx, requester, projectId)) return@launch
            runCatchingCancellable { service.getRoles(projectId) }
                .onSuccess { roles ->
                    val arr = JsonArray(
                        roles.map {
                            JsonObject().put(
                                "roleName",
                                it.roleName,
                            ).put("permissions", it.permissions).put("priority", it.priority)
                        },
                    )
                    ctx.response().setStatusCode(200).putHeader("Content-Type", "application/json").end(arr.encode())
                }
                .onFailure { respondFailure(ctx, 500, it) }
        }
    }

    fun createRole(ctx: RoutingContext) {
        val projectId = ctx.pathParam("projectId")?.toLongOrNull() ?: run {
            ctx.response().setStatusCode(400).end(errorBody("Invalid projectId"))
            return
        }
        val requester = requesterOrReject(ctx) ?: return
        val body = runCatching { ctx.body().asJsonObject() }.getOrNull() ?: run {
            ctx.response().setStatusCode(400).end(errorBody("Invalid JSON body"))
            return
        }
        val rawRoleName = body.getValue("roleName")
        val roleName = rawRoleName as? String ?: run {
            val message = if (rawRoleName == null) "roleName is required" else "roleName은 문자열이어야 합니다."
            ctx.response().setStatusCode(400).end(errorBody(message))
            return
        }
        val permissions = runCatching { body.getJsonObject("permissions") ?: JsonObject() }.getOrNull() ?: run {
            ctx.response().setStatusCode(400).end(errorBody("permissions는 객체여야 합니다."))
            return
        }
        val priority = runCatching { body.getInteger("priority", 0) }.getOrNull() ?: run {
            ctx.response().setStatusCode(400).end(errorBody("priority는 정수여야 합니다."))
            return
        }
        scope.launch(ctx.vertx().dispatcher()) {
            service.createRole(projectId, roleName, permissions, requester, priority)
                .onSuccess {
                    val resp = JsonObject().put(
                        "roleName",
                        it.roleName,
                    ).put("permissions", it.permissions).put("priority", it.priority)
                    ctx.response().setStatusCode(201).putHeader("Content-Type", "application/json").end(resp.encode())
                }
                .onFailure { e ->
                    val status = auditedAuthorizationStatus(e, requester, projectId) ?: when (e) {
                        is IllegalArgumentException -> 400
                        is IllegalStateException -> 409
                        else -> 500
                    }
                    respondFailure(ctx, status, e)
                }
        }
    }

    fun deleteRole(ctx: RoutingContext) {
        val projectId = ctx.pathParam("projectId")?.toLongOrNull() ?: run {
            ctx.response().setStatusCode(400).end(errorBody("Invalid projectId"))
            return
        }
        val roleName = ctx.pathParam("roleName") ?: run {
            ctx.response().setStatusCode(400).end(errorBody("Invalid roleName"))
            return
        }
        val requester = requesterOrReject(ctx) ?: return
        scope.launch(ctx.vertx().dispatcher()) {
            service.deleteRole(projectId, roleName, requester)
                .onSuccess { ctx.response().setStatusCode(204).end() }
                .onFailure { e ->
                    val status = auditedAuthorizationStatus(e, requester, projectId) ?: when (e) {
                        is NoSuchElementException -> 404
                        is IllegalArgumentException -> 400
                        else -> 500
                    }
                    respondFailure(ctx, status, e)
                }
        }
    }

    fun getMemberRoles(ctx: RoutingContext) {
        val projectId = ctx.pathParam("projectId")?.toLongOrNull() ?: run {
            ctx.response().setStatusCode(400).end(errorBody("Invalid projectId"))
            return
        }
        val requester = requesterOrReject(ctx) ?: return
        scope.launch(ctx.vertx().dispatcher()) {
            if (!authorizeMember(ctx, requester, projectId)) return@launch
            runCatchingCancellable { service.getMemberRoles(projectId) }
                .onSuccess { members ->
                    val arr = JsonArray(
                        members.map {
                            JsonObject().put("accountId", it.accountId).put("roleName", it.roleName)
                        },
                    )
                    ctx.response().setStatusCode(200).putHeader("Content-Type", "application/json").end(arr.encode())
                }
                .onFailure { respondFailure(ctx, 500, it) }
        }
    }

    fun assignRole(ctx: RoutingContext) {
        val projectId = ctx.pathParam("projectId")?.toLongOrNull() ?: run {
            ctx.response().setStatusCode(400).end(errorBody("Invalid projectId"))
            return
        }
        val roleName = ctx.pathParam("roleName") ?: run {
            ctx.response().setStatusCode(400).end(errorBody("Invalid roleName"))
            return
        }
        val requester = requesterOrReject(ctx) ?: return
        val body = runCatching { ctx.body().asJsonObject() }.getOrNull() ?: run {
            ctx.response().setStatusCode(400).end(errorBody("Invalid JSON body"))
            return
        }
        val accountId = runCatching { body.getLong("accountId") }.getOrElse {
            ctx.response().setStatusCode(400).end(errorBody("accountId는 정수여야 합니다."))
            return
        } ?: run {
            ctx.response().setStatusCode(400).end(errorBody("accountId is required"))
            return
        }
        scope.launch(ctx.vertx().dispatcher()) {
            service.assignRole(accountId, projectId, roleName, requester)
                .onSuccess { ctx.response().setStatusCode(204).end() }
                .onFailure { e ->
                    val status = auditedAuthorizationStatus(e, requester, projectId) ?: when (e) {
                        is NoSuchElementException -> 404
                        is IllegalArgumentException -> 400
                        else -> 500
                    }
                    respondFailure(ctx, status, e)
                }
        }
    }

    fun removeRole(ctx: RoutingContext) {
        val projectId = ctx.pathParam("projectId")?.toLongOrNull() ?: run {
            ctx.response().setStatusCode(400).end(errorBody("Invalid projectId"))
            return
        }
        val roleName = ctx.pathParam("roleName") ?: run {
            ctx.response().setStatusCode(400).end(errorBody("Invalid roleName"))
            return
        }
        val accountId = ctx.pathParam("accountId")?.toLongOrNull() ?: run {
            ctx.response().setStatusCode(400).end(errorBody("Invalid accountId"))
            return
        }
        val requester = requesterOrReject(ctx) ?: return
        scope.launch(ctx.vertx().dispatcher()) {
            runCatchingCancellable { service.removeRole(accountId, projectId, roleName, requester) }
                .onSuccess { ctx.response().setStatusCode(204).end() }
                .onFailure { e ->
                    respondFailure(ctx, auditedAuthorizationStatus(e, requester, projectId) ?: 500, e)
                }
        }
    }

    /** 요청자 헤더가 없거나 잘못되면 400으로 응답하고 null을 반환한다. */
    private fun requesterOrReject(ctx: RoutingContext): RequesterContext? = RequesterContext.from(ctx) ?: run {
        ctx.response().setStatusCode(400).end(errorBody("Missing or invalid requester headers"))
        null
    }

    /** 응답을 이미 보냈으면 false를 반환한다. */
    private suspend fun authorizeMember(ctx: RoutingContext, requester: RequesterContext, projectId: Long): Boolean =
        authorize(ctx, requester, projectId) { projectAccessGuard.requireMember(projectId, requester) }

    private suspend fun authorize(
        ctx: RoutingContext,
        requester: RequesterContext,
        projectId: Long,
        check: suspend () -> Unit,
    ): Boolean {
        val failure = runCatchingCancellable { check() }.exceptionOrNull() ?: return true
        respondFailure(ctx, auditedAuthorizationStatus(failure, requester, projectId) ?: 500, failure)
        return false
    }

    /** 500 응답은 내부 메시지를 숨기고 원인을 로그로 남긴다. */
    private fun respondFailure(ctx: RoutingContext, status: Int, failure: Throwable) {
        if (status == 500) logger().error("Failed project role operation", failure)
        val message = if (status == 500) "프로젝트 역할을 처리할 수 없습니다." else failure.message
        ctx.response().setStatusCode(status).end(errorBody(message))
    }

    private fun auditedAuthorizationStatus(failure: Throwable, requester: RequesterContext, projectId: Long): Int? =
        authorizationStatus(failure)?.also { status ->
            auditAuthorizationFailure(logger(), status, requester.userId, "PROJECT_ROLE", projectId)
        }

    private fun errorBody(message: String?) = JsonObject().put("error", message ?: "Internal server error").encode()
}
