package com.cowork.preference.handler

import com.cowork.preference.service.ProjectRoleService
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class ProjectRoleHandler(
    private val service: ProjectRoleService,
    private val scope: CoroutineScope,
) {

    fun getRoles(ctx: RoutingContext) {
        val projectId = ctx.pathParam("projectId").toLongOrNull() ?: run {
            ctx.response().setStatusCode(400).end(errorBody("Invalid projectId"))
            return
        }
        scope.launch(ctx.vertx().dispatcher()) {
            runCatching { service.getRoles(projectId) }
                .onSuccess { roles ->
                    val arr = io.vertx.core.json.JsonArray(roles.map {
                        JsonObject().put("roleName", it.roleName).put("permissions", it.permissions)
                    })
                    ctx.response().setStatusCode(200).putHeader("Content-Type", "application/json").end(arr.encode())
                }
                .onFailure { ctx.response().setStatusCode(500).end(errorBody(it.message)) }
        }
    }

    fun createRole(ctx: RoutingContext) {
        val projectId = ctx.pathParam("projectId").toLongOrNull() ?: run {
            ctx.response().setStatusCode(400).end(errorBody("Invalid projectId"))
            return
        }
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
            service.createRole(projectId, roleName, permissions)
                .onSuccess {
                    val resp = JsonObject().put("roleName", it.roleName).put("permissions", it.permissions)
                    ctx.response().setStatusCode(201).putHeader("Content-Type", "application/json").end(resp.encode())
                }
                .onFailure { e ->
                    val status = if (e is IllegalStateException) 409 else 500
                    ctx.response().setStatusCode(status).end(errorBody(e.message))
                }
        }
    }

    fun deleteRole(ctx: RoutingContext) {
        val projectId = ctx.pathParam("projectId").toLongOrNull() ?: run {
            ctx.response().setStatusCode(400).end(errorBody("Invalid projectId"))
            return
        }
        val roleName = ctx.pathParam("roleName") ?: run {
            ctx.response().setStatusCode(400).end(errorBody("Invalid roleName"))
            return
        }
        scope.launch(ctx.vertx().dispatcher()) {
            service.deleteRole(projectId, roleName)
                .onSuccess { ctx.response().setStatusCode(204).end() }
                .onFailure { e ->
                    val status = if (e is NoSuchElementException) 404 else 500
                    ctx.response().setStatusCode(status).end(errorBody(e.message))
                }
        }
    }

    fun getMemberRoles(ctx: RoutingContext) {
        val projectId = ctx.pathParam("projectId").toLongOrNull() ?: run {
            ctx.response().setStatusCode(400).end(errorBody("Invalid projectId"))
            return
        }
        scope.launch(ctx.vertx().dispatcher()) {
            runCatching { service.getMemberRoles(projectId) }
                .onSuccess { members ->
                    val arr = io.vertx.core.json.JsonArray(members.map {
                        JsonObject().put("accountId", it.accountId).put("roleName", it.roleName)
                    })
                    ctx.response().setStatusCode(200).putHeader("Content-Type", "application/json").end(arr.encode())
                }
                .onFailure { ctx.response().setStatusCode(500).end(errorBody(it.message)) }
        }
    }

    fun assignRole(ctx: RoutingContext) {
        val projectId = ctx.pathParam("projectId").toLongOrNull() ?: run {
            ctx.response().setStatusCode(400).end(errorBody("Invalid projectId"))
            return
        }
        val roleName = ctx.pathParam("roleName") ?: run {
            ctx.response().setStatusCode(400).end(errorBody("Invalid roleName"))
            return
        }
        val body = runCatching { ctx.body().asJsonObject() }.getOrNull() ?: run {
            ctx.response().setStatusCode(400).end(errorBody("Invalid JSON body"))
            return
        }
        val accountId = body.getLong("accountId") ?: run {
            ctx.response().setStatusCode(400).end(errorBody("accountId is required"))
            return
        }
        scope.launch(ctx.vertx().dispatcher()) {
            service.assignRole(accountId, projectId, roleName)
                .onSuccess { ctx.response().setStatusCode(204).end() }
                .onFailure { e ->
                    val status = if (e is NoSuchElementException) 404 else 500
                    ctx.response().setStatusCode(status).end(errorBody(e.message))
                }
        }
    }

    fun removeRole(ctx: RoutingContext) {
        val projectId = ctx.pathParam("projectId").toLongOrNull() ?: run {
            ctx.response().setStatusCode(400).end(errorBody("Invalid projectId"))
            return
        }
        val roleName = ctx.pathParam("roleName") ?: run {
            ctx.response().setStatusCode(400).end(errorBody("Invalid roleName"))
            return
        }
        val accountId = ctx.pathParam("accountId").toLongOrNull() ?: run {
            ctx.response().setStatusCode(400).end(errorBody("Invalid accountId"))
            return
        }
        scope.launch(ctx.vertx().dispatcher()) {
            runCatching { service.removeRole(accountId, projectId, roleName) }
                .onSuccess { ctx.response().setStatusCode(204).end() }
                .onFailure { ctx.response().setStatusCode(500).end(errorBody(it.message)) }
        }
    }

    private fun errorBody(message: String?) =
        JsonObject().put("error", message ?: "Internal server error").encode()
}
