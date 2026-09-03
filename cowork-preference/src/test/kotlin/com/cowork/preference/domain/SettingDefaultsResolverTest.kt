package com.cowork.preference.domain

import io.vertx.core.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SettingDefaultsResolverTest {

    @Test
    fun `configured keys preserve supplied values fill defaults and drop unknown keys`() {
        val settings = JsonObject()
            .put("enabled", false)
            .put("unknown", "ignored")
        val defaults: Map<String, Any> = linkedMapOf(
            "enabled" to true,
            "retry_count" to 3,
        )

        val resolved = SettingDefaultsResolver.resolve(settings, defaults)

        assertEquals(JsonObject().put("enabled", false).put("retry_count", 3), resolved)
        assertEquals(setOf("enabled", "retry_count"), resolved.fieldNames())
    }
}
