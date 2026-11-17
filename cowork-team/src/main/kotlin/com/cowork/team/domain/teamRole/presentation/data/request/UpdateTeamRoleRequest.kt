package com.cowork.team.domain.teamRole.presentation.data.request

import io.swagger.v3.oas.annotations.ExternalDocumentation
import io.swagger.v3.oas.annotations.media.Schema

data class UpdateTeamRoleRequest(
    @param:Schema(description = "역할 이름", minLength = 1, maxLength = 50, pattern = "\\S", example = "Backend")
    val name: String? = null,
    @param:Schema(description = "역할 색상", pattern = "^#[0-9A-Fa-f]{6}$", example = "#57F287")
    val colorHex: String? = null,
    @param:Schema(description = "값이 클수록 상위 역할", maximum = "2147483646", example = "20")
    val priority: Int? = null,
    @param:Schema(description = "역할 멘션 허용 여부", example = "false")
    val mentionable: Boolean? = null,
    @param:Schema(
        description = "생략한 권한은 유지",
        type = "object",
        additionalPropertiesSchema = Boolean::class,
        externalDocs = ExternalDocumentation(url = "/api/preference/preferences/role-permissions"),
        example = "{\"message_send\":false}",
    )
    val permissions: Map<String, Any?>? = null,
)
