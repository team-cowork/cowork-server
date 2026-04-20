package com.cowork.preference.handler

import com.cowork.preference.service.NotificationService
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class NotificationHandler(
    private val service: NotificationService,
    private val scope: CoroutineScope,
) {

    fun getNotification(ctx: RoutingContext) {
        val accountId = ctx.pathParam("accountId").toLongOrNull()
        val channelId = ctx.pathParam("channelId").toLongOrNull()
        if (accountId == null || channelId == null) {
            ctx.response().setStatusCode(400).end(errorBody("Invalid path parameters"))
            return
        }
        scope.launch(ctx.vertx().dispatcher()) {
            runCatching { service.getNotification(accountId, channelId) }
                .onSuccess { ctx.response().setStatusCode(200).putHeader("Content-Type", "application/json").end(it.encode()) }
                .onFailure { ctx.response().setStatusCode(500).end(errorBody(it.message)) }
        }
    }

    fun updateNotification(ctx: RoutingContext) {
        val accountId = ctx.pathParam("accountId").toLongOrNull()
        val channelId = ctx.pathParam("channelId").toLongOrNull()
        if (accountId == null || channelId == null) {
            ctx.response().setStatusCode(400).end(errorBody("Invalid path parameters"))
            return
        }
        val body = runCatching { ctx.body().asJsonObject() }.getOrNull()
        if (body == null) {
            ctx.response().setStatusCode(400).end(errorBody("Invalid JSON body"))
            return
        }
        scope.launch(ctx.vertx().dispatcher()) {
            runCatching { service.updateNotification(accountId, channelId, body) }
                .onSuccess { ctx.response().setStatusCode(200).putHeader("Content-Type", "application/json").end(it.encode()) }
                .onFailure { ctx.response().setStatusCode(500).end(errorBody(it.message)) }
        }
    }

    private fun errorBody(message: String?) =
        JsonObject().put("error", message ?: "Internal server error").encode()
}
