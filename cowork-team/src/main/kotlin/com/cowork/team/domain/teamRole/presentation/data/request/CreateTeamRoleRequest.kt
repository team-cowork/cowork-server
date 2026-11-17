package com.cowork.team.domain.teamRole.presentation.data.request

import io.swagger.v3.oas.annotations.ExternalDocumentation
import io.swagger.v3.oas.annotations.media.Schema

data class CreateTeamRoleRequest(
    @param:Schema(description = "역할 이름", minLength = 1, maxLength = 50, pattern = "\\S", example = "Frontend")
    val name: String,
    @param:Schema(description = "역할 색상", pattern = "^#[0-9A-Fa-f]{6}$", defaultValue = "#99AAB5", example = "#5865F2")
    val colorHex: String = "#99AAB5",
    @param:Schema(description = "값이 클수록 상위 역할", maximum = "2147483646", defaultValue = "0", example = "10")
    val priority: Int = 0,
    @param:Schema(description = "역할 멘션 허용 여부", defaultValue = "false", example = "true")
    val mentionable: Boolean = false,
    @param:Schema(
        description = "생략한 권한은 false",
        type = "object",
        additionalPropertiesSchema = Boolean::class,
        externalDocs = ExternalDocumentation(url = "/api/preference/preferences/role-permissions"),
        example = "{\"manage_roles\":true,\"message_send\":true}",
    )
    val permissions: Map<String, Any?> = emptyMap(),
)
