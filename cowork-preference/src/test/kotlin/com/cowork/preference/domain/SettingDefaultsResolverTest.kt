package com.cowork.preference.domain

import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SettingDefaultsResolverTest {

    @Test
    fun `설정한 키만 유지하고 입력값에 기본값을 합친다`() {
        val settings = JsonObject()
            .put("enabled", false)
            .put("unknown", "ignored")
        val defaults: Map<String, Any> = linkedMapOf(
            "enabled" to true,
            "page_size" to 20,
        )

        val resolved = SettingDefaultsResolver.resolve(settings, defaults)

        assertEquals(JsonObject().put("enabled", false).put("page_size", 20), resolved)
        assertEquals(setOf("enabled", "page_size"), resolved.fieldNames())
    }
}
