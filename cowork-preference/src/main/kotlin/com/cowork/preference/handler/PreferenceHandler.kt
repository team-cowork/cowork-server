package com.cowork.preference.handler

import com.cowork.preference.domain.ResourceType
import com.cowork.preference.security.RequesterContext
import com.cowork.preference.service.PreferenceService
import com.cowork.preference.service.TeamMembershipDeniedException
import com.cowork.preference.service.TeamMembershipGuard
import com.cowork.preference.service.TeamMembershipProjectionNotReadyException
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class PreferenceHandler(
    private val service: PreferenceService,
    private val scope: CoroutineScope,
    private val teamMembershipGuard: TeamMembershipGuard,
) {

    fun getSettings(resourceType: ResourceType): (RoutingContext) -> Unit = handler@{ ctx ->
        val resourceId = ctx.pathParam("id")?.toLongOrNull()
        if (resourceId == null) {
            ctx.response().setStatusCode(400).end(errorBody("Invalid resource id"))
            return@handler
        }
        val requester = if (resourceType == ResourceType.TEAM) {
            RequesterContext.from(ctx) ?: run {
                ctx.response().setStatusCode(400).end(errorBody("Missing or invalid requester headers"))
                return@handler
            }
        } else {
            null
        }
        scope.launch(ctx.vertx().dispatcher()) {
            runCatching {
                if (resourceType == ResourceType.TEAM) {
                    teamMembershipGuard.requireMember(resourceId, requireNotNull(requester))
                }
                service.getSettings(resourceType, resourceId)
            }
                .onSuccess {
                    ctx.response().setStatusCode(200).putHeader("Content-Type", "application/json").end(it.encode())
                }
                .onFailure { e -> ctx.response().setStatusCode(authorizationAwareStatus(e)).end(errorBody(e.message)) }
        }
    }

    fun getSettingsBulk(resourceType: ResourceType): (RoutingContext) -> Unit = handler@{ ctx ->
        val idsParam = ctx.request().getParam("ids")
        val resourceIds = idsParam?.split(",")?.mapNotNull { it.trim().toLongOrNull() }.orEmpty()
        if (idsParam.isNullOrBlank() || resourceIds.isEmpty()) {
            ctx.response().setStatusCode(400).end(errorBody("Invalid or missing 'ids' query parameter"))
            return@handler
        }
        scope.launch(ctx.vertx().dispatcher()) {
            runCatching { service.getSettingsBulk(resourceType, resourceIds) }
                .onSuccess { result ->
                    val body = JsonObject()
                    result.forEach { (id, settings) -> body.put(id.toString(), settings) }
                    ctx.response().setStatusCode(200).putHeader("Content-Type", "application/json").end(body.encode())
                }
                .onFailure { ctx.response().setStatusCode(500).end(errorBody(it.message)) }
        }
    }

    fun updateSettings(resourceType: ResourceType): (RoutingContext) -> Unit = handler@{ ctx ->
        val resourceId = ctx.pathParam("id")?.toLongOrNull()
        if (resourceId == null) {
            ctx.response().setStatusCode(400).end(errorBody("Invalid resource id"))
            return@handler
        }
        val requester = if (resourceType == ResourceType.TEAM) {
            RequesterContext.from(ctx) ?: run {
                ctx.response().setStatusCode(400).end(errorBody("Missing or invalid requester headers"))
                return@handler
            }
        } else {
            null
        }
        val body = runCatching { ctx.body().asJsonObject() }.getOrNull()
        if (body == null) {
            ctx.response().setStatusCode(400).end(errorBody("Invalid JSON body"))
            return@handler
        }
        scope.launch(ctx.vertx().dispatcher()) {
            val result = runCatching {
                if (resourceType == ResourceType.TEAM) {
                    teamMembershipGuard.requireSettingsManager(resourceId, requireNotNull(requester))
                }
            }.fold(
                onSuccess = { service.updateSettings(resourceType, resourceId, body) },
                onFailure = { Result.failure(it) },
            )
            result
                .onSuccess {
                    ctx.response().setStatusCode(200).putHeader("Content-Type", "application/json").end(it.encode())
                }
                .onFailure { e ->
                    val status = if (e is IllegalArgumentException) 400 else authorizationAwareStatus(e)
                    ctx.response().setStatusCode(status).end(errorBody(e.message))
                }
        }
    }

    private fun authorizationAwareStatus(e: Throwable): Int = when (e) {
        is TeamMembershipDeniedException -> 403
        is TeamMembershipProjectionNotReadyException -> 503
        else -> 500
    }

    private fun errorBody(message: String?) = JsonObject().put("error", message ?: "Internal server error").encode()
}
