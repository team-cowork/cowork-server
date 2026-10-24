package com.cowork.preference.handler

import com.cowork.preference.domain.ResourceType
import com.cowork.preference.domain.SettingSchema
import com.cowork.preference.security.RequesterContext
import com.cowork.preference.service.AccountOwnershipGuard
import com.cowork.preference.service.ChannelAccessGuard
import com.cowork.preference.service.PreferenceService
import com.cowork.preference.service.ProjectAccessGuard
import com.cowork.preference.service.ResourceAccessDeniedException
import com.cowork.preference.service.TeamMembershipGuard
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

class PreferenceHandler(
    private val service: PreferenceService,
    private val scope: CoroutineScope,
    private val teamMembershipGuard: TeamMembershipGuard,
    private val projectAccessGuard: ProjectAccessGuard,
    private val channelAccessGuard: ChannelAccessGuard,
) {
    private val log = LoggerFactory.getLogger(PreferenceHandler::class.java)

    fun getSettings(resourceType: ResourceType): (RoutingContext) -> Unit = handler@{ ctx ->
        val resourceId = ctx.pathParam("id")?.toLongOrNull()
        if (resourceId == null) {
            ctx.response().setStatusCode(400).end(errorBody("Invalid resource id"))
            return@handler
        }
        val requester = RequesterContext.from(ctx) ?: run {
            ctx.response().setStatusCode(400).end(errorBody("Missing or invalid requester headers"))
            return@handler
        }
        scope.launch(ctx.vertx().dispatcher()) {
            runCatching {
                requireReadAccess(resourceType, resourceId, requester)
                service.getSettings(resourceType, resourceId)
            }
                .onSuccess {
                    ctx.response().setStatusCode(200).putHeader("Content-Type", "application/json").end(it.encode())
                }
                .onFailure { e -> respondFailure(ctx, e, 500, resourceType, resourceId, requester) }
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
        val requester = RequesterContext.from(ctx) ?: run {
            ctx.response().setStatusCode(400).end(errorBody("Missing or invalid requester headers"))
            return@handler
        }
        val body = runCatching { ctx.body().asJsonObject() }.getOrNull()
        if (body == null) {
            ctx.response().setStatusCode(400).end(errorBody("Invalid JSON body"))
            return@handler
        }
        scope.launch(ctx.vertx().dispatcher()) {
            runCatching {
                requireWriteAccess(resourceType, resourceId, requester, body)
                service.updateSettings(resourceType, resourceId, body).getOrThrow()
            }
                .onSuccess {
                    ctx.response().setStatusCode(200).putHeader("Content-Type", "application/json").end(it.encode())
                }
                .onFailure { e ->
                    val fallback = if (e is IllegalArgumentException) 400 else 500
                    respondFailure(ctx, e, fallback, resourceType, resourceId, requester)
                }
        }
    }

    /** 공개 라우트에 없는 GITHUB_REPO는 허용 규칙이 없으므로 거부한다. */
    private suspend fun requireReadAccess(resourceType: ResourceType, resourceId: Long, requester: RequesterContext) {
        when (resourceType) {
            ResourceType.ACCOUNT -> AccountOwnershipGuard.requireOwner(resourceId, requester)
            ResourceType.TEAM -> teamMembershipGuard.requireMember(resourceId, requester)
            ResourceType.PROJECT -> projectAccessGuard.requireMember(resourceId, requester)
            ResourceType.VOICE_CHANNEL, ResourceType.TEXT_CHANNEL ->
                channelAccessGuard.requireChannelAccess(resourceId, requester.userId)
            ResourceType.GITHUB_REPO -> throw ResourceAccessDeniedException("지원하지 않는 리소스입니다.")
        }
    }

    private suspend fun requireWriteAccess(
        resourceType: ResourceType,
        resourceId: Long,
        requester: RequesterContext,
        body: JsonObject,
    ) {
        when (resourceType) {
            ResourceType.ACCOUNT -> AccountOwnershipGuard.requireOwner(resourceId, requester)
            ResourceType.TEAM -> teamMembershipGuard.requireSettingsManager(
                resourceId,
                requester,
                SettingSchema.filter(resourceType, body).fieldNames(),
            )
            ResourceType.PROJECT -> projectAccessGuard.requireManager(resourceId, requester)
            ResourceType.VOICE_CHANNEL, ResourceType.TEXT_CHANNEL ->
                channelAccessGuard.requireSettingsManager(resourceId, requester)
            ResourceType.GITHUB_REPO -> throw ResourceAccessDeniedException("지원하지 않는 리소스입니다.")
        }
    }

    private fun respondFailure(
        ctx: RoutingContext,
        e: Throwable,
        fallbackStatus: Int,
        resourceType: ResourceType,
        resourceId: Long,
        requester: RequesterContext,
    ) {
        val authorizationStatus = authorizationStatus(e)
        if (authorizationStatus != null) {
            auditAuthorizationFailure(log, authorizationStatus, requester.userId, resourceType.name, resourceId)
        }
        ctx.response().setStatusCode(authorizationStatus ?: fallbackStatus).end(errorBody(e.message))
    }

    private fun errorBody(message: String?) = JsonObject().put("error", message ?: "Internal server error").encode()
}
