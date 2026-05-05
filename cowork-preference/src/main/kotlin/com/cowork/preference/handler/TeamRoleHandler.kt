package com.cowork.preference.handler

import com.cowork.preference.domain.TeamRoleDefinition
import com.cowork.preference.service.TeamRoleService
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class TeamRoleHandler(
    private val service: TeamRoleService,
    private val scope: CoroutineScope,
) {

    fun getRoles(ctx: RoutingContext) {
        val teamId = parseLong(ctx, "teamId") ?: return
        scope.launch(ctx.vertx().dispatcher()) {
            runCatching { service.getRoles(teamId) }
                .onSuccess { ctx.json(rolesBody(it), 200) }
                .onFailure { ctx.response().setStatusCode(statusOf(it)).end(errorBody(it.message)) }
        }
    }

    fun createRole(ctx: RoutingContext) {
        val teamId = parseLong(ctx, "teamId") ?: return
        val body = body(ctx) ?: return
        val name = body.getString("name") ?: run {
            ctx.response().setStatusCode(400).end(errorBody("name is required"))
            return
        }
        scope.launch(ctx.vertx().dispatcher()) {
            service.createRole(
                teamId = teamId,
                name = name,
                colorHex = body.getString("colorHex", "#99AAB5"),
                priority = body.getInteger("priority", 0),
                mentionable = body.getBoolean("mentionable", false),
                permissions = body.getJsonArray("permissions")?.map { it.toString() }?.toSet() ?: emptySet(),
            ).onSuccess {
                ctx.json(roleBody(it), 201)
            }.onFailure { e ->
                ctx.response().setStatusCode(statusOf(e)).end(errorBody(e.message))
            }
        }
    }

    fun updateRole(ctx: RoutingContext) {
        val teamId = parseLong(ctx, "teamId") ?: return
        val roleId = parseLong(ctx, "roleId") ?: return
        val body = body(ctx) ?: return
        scope.launch(ctx.vertx().dispatcher()) {
            service.updateRole(
                teamId = teamId,
                roleId = roleId,
                name = body.getString("name"),
                colorHex = body.getString("colorHex"),
                priority = body.getInteger("priority"),
                mentionable = body.getBoolean("mentionable"),
                permissions = body.getJsonArray("permissions")?.map { it.toString() }?.toSet(),
            ).onSuccess {
                ctx.json(roleBody(it), 200)
            }.onFailure { e ->
                ctx.response().setStatusCode(statusOf(e)).end(errorBody(e.message))
            }
        }
    }

    fun deleteRole(ctx: RoutingContext) {
        val teamId = parseLong(ctx, "teamId") ?: return
        val roleId = parseLong(ctx, "roleId") ?: return
        scope.launch(ctx.vertx().dispatcher()) {
            service.deleteRole(teamId, roleId)
                .onSuccess { ctx.response().setStatusCode(204).end() }
                .onFailure { e -> ctx.response().setStatusCode(statusOf(e)).end(errorBody(e.message)) }
        }
    }

    fun getMemberRoles(ctx: RoutingContext) {
        val teamId = parseLong(ctx, "teamId") ?: return
        scope.launch(ctx.vertx().dispatcher()) {
            runCatching { service.getMemberRoles(teamId) }
                .onSuccess { members ->
                    val arr = JsonArray(members.map {
                        JsonObject()
                            .put("accountId", it.accountId)
                            .put("teamId", it.teamId)
                            .put("roleId", it.roleId)
                    })
                    ctx.json(arr, 200)
                }
                .onFailure { ctx.response().setStatusCode(statusOf(it)).end(errorBody(it.message)) }
        }
    }

    fun getMemberRoleDefinitions(ctx: RoutingContext) {
        val teamId = parseLong(ctx, "teamId") ?: return
        val accountId = parseLong(ctx, "accountId") ?: return
        scope.launch(ctx.vertx().dispatcher()) {
            runCatching { service.getMemberRoleDefinitions(teamId, accountId) }
                .onSuccess { ctx.json(rolesBody(it), 200) }
                .onFailure { ctx.response().setStatusCode(statusOf(it)).end(errorBody(it.message)) }
        }
    }

    fun assignRole(ctx: RoutingContext) {
        val teamId = parseLong(ctx, "teamId") ?: return
        val roleId = parseLong(ctx, "roleId") ?: return
        val body = body(ctx) ?: return
        val accountId = body.getLong("accountId") ?: run {
            ctx.response().setStatusCode(400).end(errorBody("accountId is required"))
            return
        }
        scope.launch(ctx.vertx().dispatcher()) {
            service.assignRole(accountId, teamId, roleId)
                .onSuccess { ctx.json(roleBody(it), 200) }
                .onFailure { e -> ctx.response().setStatusCode(statusOf(e)).end(errorBody(e.message)) }
        }
    }

    fun removeRole(ctx: RoutingContext) {
        val teamId = parseLong(ctx, "teamId") ?: return
        val roleId = parseLong(ctx, "roleId") ?: return
        val accountId = parseLong(ctx, "accountId") ?: return
        scope.launch(ctx.vertx().dispatcher()) {
            runCatching { service.removeRole(accountId, teamId, roleId) }
                .onSuccess { ctx.response().setStatusCode(204).end() }
                .onFailure { ctx.response().setStatusCode(statusOf(it)).end(errorBody(it.message)) }
        }
    }

    fun removeMemberRoles(ctx: RoutingContext) {
        val teamId = parseLong(ctx, "teamId") ?: return
        val accountId = parseLong(ctx, "accountId") ?: return
        scope.launch(ctx.vertx().dispatcher()) {
            runCatching { service.removeMemberRoles(accountId, teamId) }
                .onSuccess { ctx.response().setStatusCode(204).end() }
                .onFailure { ctx.response().setStatusCode(statusOf(it)).end(errorBody(it.message)) }
        }
    }

    private fun body(ctx: RoutingContext): JsonObject? =
        runCatching { ctx.body().asJsonObject() }.getOrNull() ?: run {
            ctx.response().setStatusCode(400).end(errorBody("Invalid JSON body"))
            null
        }

    private fun parseLong(ctx: RoutingContext, name: String): Long? =
        ctx.pathParam(name).toLongOrNull() ?: run {
            ctx.response().setStatusCode(400).end(errorBody("Invalid $name"))
            null
        }

    private fun rolesBody(roles: List<TeamRoleDefinition>): JsonArray =
        JsonArray(roles.map { roleBody(it) })

    private fun roleBody(role: TeamRoleDefinition): JsonObject =
        JsonObject()
            .put("id", role.id)
            .put("teamId", role.teamId)
            .put("name", role.name)
            .put("colorHex", role.colorHex)
            .put("priority", role.priority)
            .put("mentionable", role.mentionable)
            .put("permissions", JsonArray(role.permissions.toList()))
            .put("createdAt", role.createdAt?.toString())
            .put("updatedAt", role.updatedAt?.toString())

    private fun RoutingContext.json(body: Any, status: Int) {
        response()
            .setStatusCode(status)
            .putHeader("Content-Type", "application/json")
            .end(
                when (body) {
                    is JsonObject -> body.encode()
                    is JsonArray -> body.encode()
                    else -> JsonObject.mapFrom(body).encode()
                }
            )
    }

    private fun statusOf(error: Throwable): Int =
        when (error) {
            is IllegalArgumentException -> 400
            is NoSuchElementException -> 404
            is IllegalStateException -> 409
            else -> 500
        }

    private fun errorBody(message: String?) =
        JsonObject().put("error", message ?: "Internal server error").encode()
}
