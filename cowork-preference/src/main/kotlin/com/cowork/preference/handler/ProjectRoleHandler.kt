package com.cowork.preference.handler

import com.cowork.preference.security.RequesterContext
import com.cowork.preference.service.ProjectAccessGuard
import com.cowork.preference.service.ProjectRoleService
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

class ProjectRoleHandler(
    private val service: ProjectRoleService,
    private val scope: CoroutineScope,
    private val projectAccessGuard: ProjectAccessGuard,
) {
    private val log = LoggerFactory.getLogger(ProjectRoleHandler::class.java)

    fun getRoles(ctx: RoutingContext) {
        val projectId = ctx.pathParam("projectId")?.toLongOrNull() ?: run {
            ctx.response().setStatusCode(400).end(errorBody("Invalid projectId"))
            return
        }
        val requester = requesterOrReject(ctx) ?: return
        scope.launch(ctx.vertx().dispatcher()) {
            if (!authorizeMember(ctx, requester, projectId)) return@launch
            runCatching { service.getRoles(projectId) }
                .onSuccess { roles ->
                    val arr = JsonArray(roles.map {
                        JsonObject().put("roleName", it.roleName).put("permissions", it.permissions).put("priority", it.priority)
                    })
                    ctx.response().setStatusCode(200).putHeader("Content-Type", "application/json").end(arr.encode())
                }
                .onFailure { ctx.response().setStatusCode(500).end(errorBody(it.message)) }
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
        val roleName = body.getString("roleName") ?: run {
            ctx.response().setStatusCode(400).end(errorBody("roleName is required"))
            return
        }
        val permissions = body.getJsonObject("permissions") ?: JsonObject()
        scope.launch(ctx.vertx().dispatcher()) {
            if (!authorize(ctx, requester, projectId) { projectAccessGuard.requireRoleCreation(projectId, requester, permissions) }) return@launch
            service.createRole(projectId, roleName, permissions, body.getInteger("priority", 0))
                .onSuccess {
                    val resp = JsonObject().put("roleName", it.roleName).put("permissions", it.permissions).put("priority", it.priority)
                    ctx.response().setStatusCode(201).putHeader("Content-Type", "application/json").end(resp.encode())
                }
                .onFailure { e ->
                    val status = when (e) {
                        is IllegalArgumentException -> 400
                        is IllegalStateException -> 409
                        else -> 500
                    }
                    ctx.response().setStatusCode(status).end(errorBody(e.message))
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
            if (!authorize(ctx, requester, projectId) { projectAccessGuard.requireRoleDeletion(projectId, requester, roleName) }) return@launch
            service.deleteRole(projectId, roleName)
                .onSuccess { ctx.response().setStatusCode(204).end() }
                .onFailure { e ->
                    val status = when (e) {
                        is NoSuchElementException -> 404
                        is IllegalArgumentException -> 400
                        else -> 500
                    }
                    ctx.response().setStatusCode(status).end(errorBody(e.message))
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
            runCatching { service.getMemberRoles(projectId) }
                .onSuccess { members ->
                    val arr = JsonArray(members.map {
                        JsonObject().put("accountId", it.accountId).put("roleName", it.roleName)
                    })
                    ctx.response().setStatusCode(200).putHeader("Content-Type", "application/json").end(arr.encode())
                }
                .onFailure { ctx.response().setStatusCode(500).end(errorBody(it.message)) }
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
        val accountId = body.getLong("accountId") ?: run {
            ctx.response().setStatusCode(400).end(errorBody("accountId is required"))
            return
        }
        scope.launch(ctx.vertx().dispatcher()) {
            if (!authorize(ctx, requester, projectId) { projectAccessGuard.requireRoleAssignment(projectId, requester, roleName, accountId) }) return@launch
            service.assignRole(accountId, projectId, roleName)
                .onSuccess { ctx.response().setStatusCode(204).end() }
                .onFailure { e ->
                    val status = when (e) {
                        is NoSuchElementException -> 404
                        is IllegalArgumentException -> 400
                        else -> 500
                    }
                    ctx.response().setStatusCode(status).end(errorBody(e.message))
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
            if (!authorize(ctx, requester, projectId) { projectAccessGuard.requireRoleRemoval(projectId, requester, roleName, accountId) }) return@launch
            runCatching { service.removeRole(accountId, projectId, roleName) }
                .onSuccess { ctx.response().setStatusCode(204).end() }
                .onFailure { ctx.response().setStatusCode(500).end(errorBody(it.message)) }
        }
    }

    /** 요청자 헤더가 없거나 잘못되면 400으로 응답하고 null을 반환한다. */
    private fun requesterOrReject(ctx: RoutingContext): RequesterContext? =
        RequesterContext.from(ctx) ?: run {
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
        val failure = runCatching { check() }.exceptionOrNull() ?: return true
        val status = authorizationStatus(failure)
        if (status != null) {
            auditAuthorizationFailure(log, status, requester.userId, "PROJECT_ROLE", projectId)
        }
        ctx.response().setStatusCode(status ?: 500).end(errorBody(failure.message))
        return false
    }

    private fun errorBody(message: String?) =
        JsonObject().put("error", message ?: "Internal server error").encode()
}
