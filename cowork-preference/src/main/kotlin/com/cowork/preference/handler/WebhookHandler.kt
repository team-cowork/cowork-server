package com.cowork.preference.handler

import com.cowork.preference.domain.WebhookRequestException
import com.cowork.preference.security.RequesterContext
import com.cowork.preference.service.WebhookService
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

class WebhookHandler(private val service: WebhookService, private val scope: CoroutineScope) {
    private val log = LoggerFactory.getLogger(WebhookHandler::class.java)

    fun routes(router: Router) {
        val collection = "/channels/:channelId/webhooks"
        val item = "$collection/:webhookId"
        router.get(collection).handler { ctx -> handle(ctx) { requester, channel -> service.list(channel, requester) } }
        router.post(collection).handler { ctx ->
            handle(ctx, 201) { requester, channel -> service.create(channel, requester, body(ctx), ctx.request().getHeader("Idempotency-Key")) }
        }
        router.patch(item).handler { ctx ->
            handle(ctx) { requester, channel -> service.patch(channel, id(ctx, "webhookId"), requester, body(ctx)) }
        }
        router.delete(item).handler { ctx ->
            handle(ctx, 204) { requester, channel -> service.delete(channel, id(ctx, "webhookId"), requester); null }
        }
        router.put("$item/security-mode").handler { ctx ->
            handle(ctx) { requester, channel ->
                service.changeMode(channel, id(ctx, "webhookId"), requester, body(ctx), ctx.request().getHeader("Idempotency-Key"))
            }
        }
    }

    private fun handle(ctx: RoutingContext, successStatus: Int = 200,
        action: suspend (RequesterContext, Long) -> Any?) {
        ctx.response().putHeader("Cache-Control", "no-store")
        scope.launch(ctx.vertx().dispatcher()) {
            try {
                val requester = RequesterContext.from(ctx) ?: throw WebhookRequestException(400, "요청자 정보가 필요합니다.")
                val result = action(requester, id(ctx, "channelId"))
                ctx.response().setStatusCode(successStatus)
                if (result == null) ctx.response().end() else {
                    ctx.response().putHeader("Content-Type", "application/json").end(result.toString())
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val status = (error as? WebhookRequestException)?.status ?: authorizationStatus(error) ?: 500
                val message = if (status == 500) "웹훅 요청을 처리할 수 없습니다." else error.message
                if (status == 500) log.error("Failed to process webhook management request", error)
                val failure = error as? WebhookRequestException
                failure?.retryAfter?.let { ctx.response().putHeader("Retry-After", it.toString()) }
                val statusName = when (status) {
                    400 -> "BAD_REQUEST"
                    403 -> "FORBIDDEN"
                    404 -> "NOT_FOUND"
                    409 -> "CONFLICT"
                    429 -> "TOO_MANY_REQUESTS"
                    503 -> "SERVICE_UNAVAILABLE"
                    else -> "INTERNAL_SERVER_ERROR"
                }
                val data = if (failure?.retryAfter != null) JsonObject()
                    .put("reason", "MODE_CHANGE_COOLDOWN").put("retryAfterSeconds", failure.retryAfter)
                    .put("nextAllowedAt", failure.nextAllowedAt?.toString()) else null
                ctx.response().setStatusCode(status).putHeader("Content-Type", "application/json").end(
                    JsonObject().put("code", status).put("status", statusName).put("message", message).put("data", data).encode(),
                )
            }
        }
    }

    private fun id(ctx: RoutingContext, key: String): Long = ctx.pathParam(key)?.toLongOrNull()?.takeIf { it > 0 }
        ?: throw WebhookRequestException(400, "올바른 리소스 ID가 필요합니다.")

    private fun body(ctx: RoutingContext): JsonObject = runCatching { ctx.body().asJsonObject() }.getOrNull()
        ?: throw WebhookRequestException(400, "올바른 JSON 객체가 필요합니다.")
}
