package com.cowork.preference.config

import io.vertx.core.json.JsonObject
import java.net.NetworkInterface

data class DbConfig(
    val host: String,
    val port: Int,
    val database: String,
    val schema: String,
    val username: String,
    val password: String,
    val poolSize: Int,
)

data class RedisConfig(val host: String, val port: Int)

data class KafkaConfig(
    val bootstrapServers: String,
    val teamMemberConsumerGroupId: String,
    val teamMemberTopic: String,
    val teamRoleCommandConsumerGroupId: String,
    val githubRepoSettingCommandConsumerGroupId: String,
)

data class AppConfig(
    val serverPort: Int,
    val db: DbConfig,
    val redis: RedisConfig,
    val kafka: KafkaConfig,
    val eurekaUrl: String,
    val eurekaEnabled: Boolean,
    val eurekaAppName: String,
    val eurekaInstanceHost: String,
    val eurekaInstanceId: String,
) {
    companion object {
        fun from(json: JsonObject): AppConfig {
            val pref = json.getJsonObject("preference") ?: JsonObject()
            val db = pref.getJsonObject("db") ?: JsonObject()
            val redis = pref.getJsonObject("redis") ?: JsonObject()
            val kafka = pref.getJsonObject("kafka") ?: JsonObject()
            val eureka = json.getJsonObject("eureka") ?: JsonObject()
            val instance = eureka.getJsonObject("instance") ?: JsonObject()
            val serverPort = json.getJsonObject("server")?.getInt("port", 9001) ?: 9001
            val appName = eureka.getString("app-name", "cowork-preference")
            val configuredInstanceHost = instance.getString("host", "localhost")
            val useRuntimeIdentity = System.getenv("EUREKA_USE_RUNTIME_HOSTNAME") == "true"
            val runtimeHostname = System.getenv("HOSTNAME")?.takeIf(String::isNotBlank)
            val instanceHost = if (useRuntimeIdentity) {
                System.getenv("EUREKA_INSTANCE_HOST")?.takeIf(String::isNotBlank)
                    ?: nonLoopbackIpv4()
                    ?: error("No non-loopback IPv4 address is available for Eureka registration")
            } else {
                configuredInstanceHost
            }
            val instanceId = System.getenv("EUREKA_INSTANCE_ID")?.takeIf(String::isNotBlank)
                ?: if (useRuntimeIdentity) {
                    val identityHost = runtimeHostname
                        ?: error("HOSTNAME is required for replica-unique Eureka identity")
                    "$identityHost:$appName:$serverPort"
                } else {
                    instance.getString("id", "$instanceHost:$appName:$serverPort")
                }
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
                    teamMemberConsumerGroupId = kafka.getString(
                        "team-member-consumer-group-id",
                        "cowork-preference-team-member-projection",
                    ),
                    teamMemberTopic = kafka.getString("team-member-topic", "team.member.event"),
                    teamRoleCommandConsumerGroupId = kafka.getString(
                        "team-role-command-consumer-group-id",
                        "cowork-preference-team-role-command",
                    ),
                    githubRepoSettingCommandConsumerGroupId = kafka.getString(
                        "github-repo-setting-command-consumer-group-id",
                        "cowork-preference-github-repo-setting-command",
                    ),
                ),
                eurekaUrl = eureka.getString("url", "http://localhost:8761/eureka/"),
                eurekaEnabled = eureka.getBoolean("enabled", true),
                eurekaAppName = appName,
                eurekaInstanceHost = instanceHost,
                eurekaInstanceId = instanceId,
            )
        }

        private fun nonLoopbackIpv4(): String? = NetworkInterface.getNetworkInterfaces().toList()
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList().asSequence() }
            .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress && it.address.size == 4 }
            ?.hostAddress

        private fun JsonObject.getInt(key: String, defaultValue: Int): Int = when (val value = getValue(key)) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull() ?: defaultValue
            else -> defaultValue
        }
    }
}
