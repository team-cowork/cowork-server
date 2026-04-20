package com.cowork.preference.domain

import io.vertx.core.json.JsonObject

data class ProjectRoleDefinition(
    val projectId: Long,
    val roleName: String,
    val permissions: JsonObject,
)

data class AccountProjectRole(
    val accountId: Long,
    val projectId: Long,
    val roleName: String,
)
