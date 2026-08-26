package com.cowork.preference

import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class OpenApiContractTest {

    @Test
    fun `통합 Swagger는 preference canonical server와 Bearer JWT를 사용한다`() {
        val document = requireNotNull(javaClass.getResourceAsStream("/openapi.json")).use { input ->
            JsonObject(input.readAllBytes().decodeToString())
        }

        assertEquals("/api/preference", document.getJsonArray("servers").getJsonObject(0).getString("url"))
        assertNotNull(
            document.getJsonObject("components")
                .getJsonObject("securitySchemes")
                .getJsonObject("BearerAuth"),
        )
        assertFalse(
            document.getJsonObject("paths").fieldNames()
                .any { it.startsWith("/preferences/team/") && it.contains("/roles") },
        )
    }
}
