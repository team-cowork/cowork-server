package com.cowork.preference.domain

import io.vertx.core.json.JsonObject

object SettingDefaultsResolver {

    fun resolve(settings: JsonObject?, defaults: Map<String, Any>): JsonObject = JsonObject().apply {
        defaults.forEach { (key, defaultValue) ->
            put(key, settings?.getValue(key) ?: defaultValue)
        }
    }
}
