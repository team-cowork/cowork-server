package com.cowork.preference.handler

import com.cowork.preference.security.RequesterContext
import com.cowork.preference.service.AccountOwnershipDeniedException
import com.cowork.preference.service.AccountOwnershipGuard
import com.cowork.preference.service.ChannelAccessGuard
import com.cowork.preference.service.NotificationService
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

class NotificationHandler(
    private val service: NotificationService,
    private val scope: CoroutineScope,
    private val channelAccessGuard: ChannelAccessGuard,
) {
    private val log = LoggerFactory.getLogger(NotificationHandler::class.java)

    fun getNotification(ctx: RoutingContext) {
        val accountId = ctx.pathParam("accountId")?.toLongOrNull()
        val channelId = ctx.pathParam("channelId")?.toLongOrNull()
        if (accountId == null || channelId == null) {
            ctx.response().setStatusCode(400).end(errorBody("Invalid path parameters"))
            return
        }
        if (!authorizeOwner(ctx, accountId)) return
        scope.launch(ctx.vertx().dispatcher()) {
            runCatching {
                channelAccessGuard.requireChannelAccess(channelId, accountId)
                service.getNotification(accountId, channelId)
            }
                .onSuccess { ctx.response().setStatusCode(200).putHeader("Content-Type", "application/json").end(it.encode()) }
                .onFailure { respondFailure(ctx, it, accountId, channelId) }
        }
    }

    fun updateNotification(ctx: RoutingContext) {
        val accountId = ctx.pathParam("accountId")?.toLongOrNull()
        val channelId = ctx.pathParam("channelId")?.toLongOrNull()
        if (accountId == null || channelId == null) {
            ctx.response().setStatusCode(400).end(errorBody("Invalid path parameters"))
            return
        }
        if (!authorizeOwner(ctx, accountId)) return
        val body = runCatching { ctx.body().asJsonObject() }.getOrNull()
        if (body == null) {
            ctx.response().setStatusCode(400).end(errorBody("Invalid JSON body"))
            return
        }
        scope.launch(ctx.vertx().dispatcher()) {
            runCatching {
                channelAccessGuard.requireChannelAccess(channelId, accountId)
                service.updateNotification(accountId, channelId, body)
            }
                .onSuccess { ctx.response().setStatusCode(200).putHeader("Content-Type", "application/json").end(it.encode()) }
                .onFailure { respondFailure(ctx, it, accountId, channelId) }
        }
    }

    /** 계정별 채널 알림은 계정 설정과 같이 본인에게만 허용한다. 응답을 이미 보냈으면 false를 반환한다. */
    private fun authorizeOwner(ctx: RoutingContext, accountId: Long): Boolean {
        val requester = RequesterContext.from(ctx) ?: run {
            ctx.response().setStatusCode(400).end(errorBody("Missing or invalid requester headers"))
            return false
        }
        try {
            AccountOwnershipGuard.requireOwner(accountId, requester)
        } catch (e: AccountOwnershipDeniedException) {
            auditAuthorizationFailure(log, 403, requester.userId, "ACCOUNT", accountId)
            ctx.response().setStatusCode(403).end(errorBody(e.message))
            return false
        }
        return true
    }

    /** 본인 확인을 통과한 요청이므로 요청자 ID는 accountId와 같다. */
    private fun respondFailure(ctx: RoutingContext, e: Throwable, accountId: Long, channelId: Long) {
        val status = authorizationStatus(e)
        if (status != null) {
            auditAuthorizationFailure(log, status, accountId, "CHANNEL_NOTIFICATION", channelId)
        }
        val fallback = if (e is IllegalArgumentException) 400 else 500
        ctx.response().setStatusCode(status ?: fallback).end(errorBody(e.message))
    }

    private fun errorBody(message: String?) =
        JsonObject().put("error", message ?: "Internal server error").encode()
}
