package com.cowork.preference.domain.setting.model

import io.vertx.core.json.JsonObject
import kotlin.reflect.KClass

data class SettingDefinition<T : Any>(
    val type: KClass<T>,
    val title: String,
    val description: String,
    val defaultValue: T? = null,
    val nullable: Boolean = false,
    val objectKeys: Set<String> = emptySet(),
    val validator: (T, JsonObject) -> String? = { _, _ -> null },
) {

    fun metadata(key: String): JsonObject = JsonObject()
        .put("key", key)
        .put("title", title)
        .put("description", description)
        .put("type", typeName)
        .put("defaultValue", resolve(null))
        .put("nullable", nullable)

    fun validate(key: String, value: Any?, settings: JsonObject): String? {
        if (value == null && nullable) return null
        if (!type.javaObjectType.isInstance(value)) return "$key must be a $typeName"
        return validator(type.javaObjectType.cast(value), settings)?.let { "$key $it" }
    }

    fun filter(value: Any?): Any? {
        if (value !is JsonObject || objectKeys.isEmpty()) return value
        return JsonObject().apply {
            objectKeys.forEach { key -> if (value.containsKey(key)) put(key, value.getValue(key)) }
        }
    }

    fun resolve(value: Any?): Any? = value ?: when (val default = defaultValue) {
        is JsonObject -> default.copy()
        else -> default
    }

    private val typeName: String
        get() = when (type) {
            Boolean::class -> "boolean"
            Int::class -> "integer"
            String::class -> "string"
            JsonObject::class -> "object"
            else -> type.simpleName ?: "${type.javaObjectType}"
        }
}
