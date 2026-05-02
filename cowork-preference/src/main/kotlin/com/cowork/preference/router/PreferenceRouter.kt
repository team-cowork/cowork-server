package com.cowork.preference.router

import com.cowork.preference.MetricsRegistry
import com.cowork.preference.domain.ResourceType
import com.cowork.preference.handler.NotificationHandler
import com.cowork.preference.handler.PreferenceHandler
import com.cowork.preference.handler.ProjectRoleHandler
import io.vertx.core.Vertx
import io.vertx.ext.web.Router
import io.vertx.ext.web.handler.BodyHandler

fun buildRouter(
    vertx: Vertx,
    preferenceHandler: PreferenceHandler,
    notificationHandler: NotificationHandler,
    projectRoleHandler: ProjectRoleHandler,
): Router {
    val router = Router.router(vertx)
    router.route().handler(BodyHandler.create())

    router.get("/health").handler { ctx ->
        ctx.response()
            .putHeader("Content-Type", "application/json")
            .end("""{"status":"UP"}""")
    }

    router.get("/metrics").handler { ctx ->
        ctx.response()
            .putHeader("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
            .end(MetricsRegistry.registry.scrape())
    }

    val openapiSpec = object {}.javaClass.getResourceAsStream("/openapi.json")?.use { it.bufferedReader().readText() }
    router.get("/swagger/doc.json").handler { ctx ->
        if (openapiSpec != null) {
            ctx.response().putHeader("Content-Type", "application/json").end(openapiSpec)
        } else {
            ctx.response().setStatusCode(404).end()
        }
    }

    // ACCOUNT
    router.get("/preferences/account/:id").handler(preferenceHandler.getSettings(ResourceType.ACCOUNT))
    router.put("/preferences/account/:id").handler(preferenceHandler.updateSettings(ResourceType.ACCOUNT))

    // TEAM
    router.get("/preferences/team/:id").handler(preferenceHandler.getSettings(ResourceType.TEAM))
    router.put("/preferences/team/:id").handler(preferenceHandler.updateSettings(ResourceType.TEAM))

    // PROJECT
    router.get("/preferences/project/:id").handler(preferenceHandler.getSettings(ResourceType.PROJECT))
    router.put("/preferences/project/:id").handler(preferenceHandler.updateSettings(ResourceType.PROJECT))
    router.get("/preferences/project/:projectId/roles").handler(projectRoleHandler::getRoles)
    router.post("/preferences/project/:projectId/roles").handler(projectRoleHandler::createRole)
    router.delete("/preferences/project/:projectId/roles/:roleName").handler(projectRoleHandler::deleteRole)
    router.get("/preferences/project/:projectId/roles/members").handler(projectRoleHandler::getMemberRoles)
    router.post("/preferences/project/:projectId/roles/:roleName/members").handler(projectRoleHandler::assignRole)
    router.delete("/preferences/project/:projectId/roles/:roleName/members/:accountId").handler(projectRoleHandler::removeRole)

    // VOICE_CHANNEL
    router.get("/preferences/voice-channel/:id").handler(preferenceHandler.getSettings(ResourceType.VOICE_CHANNEL))
    router.put("/preferences/voice-channel/:id").handler(preferenceHandler.updateSettings(ResourceType.VOICE_CHANNEL))

    // TEXT_CHANNEL
    router.get("/preferences/text-channel/:id").handler(preferenceHandler.getSettings(ResourceType.TEXT_CHANNEL))
    router.put("/preferences/text-channel/:id").handler(preferenceHandler.updateSettings(ResourceType.TEXT_CHANNEL))

    // Notification
    router.get("/preferences/account/:accountId/channels/:channelId/notification")
        .handler(notificationHandler::getNotification)
    router.put("/preferences/account/:accountId/channels/:channelId/notification")
        .handler(notificationHandler::updateNotification)

    return router
}
