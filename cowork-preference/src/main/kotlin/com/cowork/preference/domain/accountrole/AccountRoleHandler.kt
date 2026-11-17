package com.cowork.preference.domain.accountrole

import com.cowork.preference.domain.access.exception.ResourceAccessDeniedException
import com.cowork.preference.global.coroutine.runCatchingCancellable
import com.cowork.preference.global.logging.logger
import com.cowork.preference.global.security.RequesterContext
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class AccountRoleHandler(private val service: AccountRoleService, private val scope: CoroutineScope) {
    fun routes(router: Router) {
        router.get("/preferences/account-roles").handler { ctx ->
            handle(ctx, 200) { JsonArray(service.list()).encode() }
        }
        router.get("/preferences/account-roles/me").handler { ctx ->
            handle(ctx, 200) { requester -> JsonArray(service.assignments(requester)).encode() }
        }
        router.get("/preferences/account-roles/members/:accountId").handler { ctx ->
            handle(ctx, 200) { requester ->
                JsonArray(service.assignments(requester, positiveId(ctx.pathParam("accountId")))).encode()
            }
        }
        router.post("/preferences/account-roles").handler { ctx -> save(ctx, null) }
        router.put("/preferences/account-roles/:roleId").handler { ctx -> save(ctx, ctx.pathParam("roleId")) }
        router.delete("/preferences/account-roles/:roleId").handler { ctx ->
            handle(ctx, 204) { requester ->
                service.delete(requester, positiveId(ctx.pathParam("roleId")))
                ""
            }
        }
        router.put("/preferences/account-roles/:roleId/members/:accountId").handler { ctx -> assign(ctx, false) }
        router.delete("/preferences/account-roles/:roleId/members/:accountId").handler { ctx -> assign(ctx, true) }
    }

    private fun save(ctx: RoutingContext, rawId: String?) = handle(ctx, if (rawId == null) 201 else 200) { requester ->
        val body = runCatching { ctx.body().asJsonObject() }.getOrNull()
            ?: throw IllegalArgumentException("JSON 요청 본문이 필요합니다.")
        val name = body.getValue("name") as? String ?: throw IllegalArgumentException("역할 이름이 필요합니다.")
        val permissions = body.getValue("permissions") as? JsonObject ?: throw IllegalArgumentException("역할 권한이 필요합니다.")
        service.save(requester, rawId?.let(::positiveId), name, permissions).encode()
    }

    private fun assign(ctx: RoutingContext, deleted: Boolean) = handle(ctx, 204) { requester ->
        service.assign(requester, positiveId(ctx.pathParam("roleId")), positiveId(ctx.pathParam("accountId")), deleted)
        ""
    }

    private fun positiveId(value: String?): Long = value?.toLongOrNull()?.takeIf { it > 0 }
        ?: throw IllegalArgumentException("ID는 양수여야 합니다.")

    private fun handle(ctx: RoutingContext, status: Int, action: suspend (RequesterContext) -> String) {
        val requester = RequesterContext.from(ctx)
        if (requester == null) {
            ctx.response().setStatusCode(401).end()
            return
        }
        scope.launch(ctx.vertx().dispatcher()) {
            runCatchingCancellable { action(requester) }.onSuccess {
                ctx.response().setStatusCode(status).putHeader("Content-Type", "application/json").end(it)
            }.onFailure { error ->
                val failureStatus = when (error) {
                    is ResourceAccessDeniedException -> 403
                    is IllegalArgumentException -> 400
                    else -> 500
                }
                if (failureStatus == 500) this@AccountRoleHandler.logger().error("Failed account role operation", error)
                val message = if (failureStatus == 500) "계정 역할을 처리할 수 없습니다." else error.message
                ctx.response().setStatusCode(failureStatus).putHeader("Content-Type", "application/json")
                    .end(JsonObject().put("error", message).encode())
            }
        }
    }
}
