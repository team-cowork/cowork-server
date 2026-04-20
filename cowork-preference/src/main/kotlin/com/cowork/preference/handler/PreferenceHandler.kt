package com.cowork.preference.handler

import com.cowork.preference.domain.ResourceType
import com.cowork.preference.service.PreferenceService
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class PreferenceHandler(
    private val service: PreferenceService,
    private val scope: CoroutineScope,
) {

    fun getSettings(resourceType: ResourceType): (RoutingContext) -> Unit = handler@{ ctx ->
        val resourceId = ctx.pathParam("id").toLongOrNull()
        if (resourceId == null) {
            ctx.response().setStatusCode(400).end(errorBody("Invalid resource id"))
            return@handler
        }
        scope.launch(ctx.vertx().dispatcher()) {
            runCatching { service.getSettings(resourceType, resourceId) }
                .onSuccess { ctx.response().setStatusCode(200).putHeader("Content-Type", "application/json").end(it.encode()) }
                .onFailure { ctx.response().setStatusCode(500).end(errorBody(it.message)) }
        }
    }

    fun updateSettings(resourceType: ResourceType): (RoutingContext) -> Unit = handler@{ ctx ->
        val resourceId = ctx.pathParam("id").toLongOrNull()
        if (resourceId == null) {
            ctx.response().setStatusCode(400).end(errorBody("Invalid resource id"))
            return@handler
        }
        val body = runCatching { ctx.body().asJsonObject() }.getOrNull()
        if (body == null) {
            ctx.response().setStatusCode(400).end(errorBody("Invalid JSON body"))
            return@handler
        }
        scope.launch(ctx.vertx().dispatcher()) {
            service.updateSettings(resourceType, resourceId, body)
                .onSuccess { ctx.response().setStatusCode(200).putHeader("Content-Type", "application/json").end(it.encode()) }
                .onFailure { e ->
                    val status = if (e is IllegalArgumentException) 400 else 500
                    ctx.response().setStatusCode(status).end(errorBody(e.message))
                }
        }
    }

    private fun errorBody(message: String?) =
        JsonObject().put("error", message ?: "Internal server error").encode()
}
