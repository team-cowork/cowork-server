package com.cowork.preference.config

import io.vertx.core.json.JsonObject

data class DbConfig(
    val host: String,
    val port: Int,
    val database: String,
    val schema: String,
    val username: String,
    val password: String,
    val poolSize: Int,
)

data class RedisConfig(
    val host: String,
    val port: Int,
)

data class KafkaConfig(
    val bootstrapServers: String,
)

data class AppConfig(
    val serverPort: Int,
    val db: DbConfig,
    val redis: RedisConfig,
    val kafka: KafkaConfig,
    val eurekaUrl: String,
    val eurekaAppName: String,
    val eurekaInstanceHost: String,
) {
    companion object {
        fun from(json: JsonObject): AppConfig {
            val pref = json.getJsonObject("preference") ?: JsonObject()
            val db = pref.getJsonObject("db") ?: JsonObject()
            val redis = pref.getJsonObject("redis") ?: JsonObject()
            val kafka = pref.getJsonObject("kafka") ?: JsonObject()
            val eureka = json.getJsonObject("eureka") ?: JsonObject()
            val serverPort = json.getJsonObject("server")?.getInt("port", 9001) ?: 9001
            return AppConfig(
                serverPort = serverPort,
                db = DbConfig(
                    host = db.getString("host", "localhost"),
                    port = db.getInt("port", 5432),
                    database = db.getString("database", "cowork_preference"),
                    schema = db.getString("schema", "preference"),
                    username = db.getString("username", "cowork"),
                    password = db.getString("password", ""),
                    poolSize = db.getInt("pool-size", 5),
                ),
                redis = RedisConfig(
                    host = redis.getString("host", "localhost"),
                    port = redis.getInt("port", 6379),
                ),
                kafka = KafkaConfig(
                    bootstrapServers = kafka.getString("bootstrap-servers", "localhost:9092"),
                ),
                eurekaUrl = eureka.getString("url", "http://localhost:8761/eureka/"),
                eurekaAppName = eureka.getString("app-name", "cowork-preference"),
                eurekaInstanceHost = eureka.getJsonObject("instance")?.getString("host", "localhost") ?: "localhost",
            )
        }

        private fun JsonObject.getInt(key: String, defaultValue: Int): Int =
            when (val value = getValue(key)) {
                is Number -> value.toInt()
                is String -> value.toIntOrNull() ?: defaultValue
                else -> defaultValue
            }
    }
}
