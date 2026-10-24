package com.cowork.preference.domain

import io.vertx.core.json.JsonObject

data class ProjectRoleDefinition(
    val projectId: Long,
    val roleName: String,
    val permissions: JsonObject,
    val priority: Int = 0,
)

data class AccountProjectRole(
    val accountId: Long,
    val projectId: Long,
    val roleName: String,
)
