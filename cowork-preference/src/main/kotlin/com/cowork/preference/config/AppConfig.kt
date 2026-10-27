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

data class RedisConfig(val host: String, val port: Int)

data class KafkaConfig(
    val bootstrapServers: String,
    val teamMemberConsumerGroupId: String,
    val teamMemberTopic: String,
    val channelLifecycleConsumerGroupId: String,
    val channelTopic: String,
    val projectLifecycleConsumerGroupId: String,
    val projectTopic: String,
    val dmParticipantConsumerGroupId: String,
    val dmParticipantTopic: String,
    val teamRoleCommandConsumerGroupId: String,
    val githubRepoSettingCommandConsumerGroupId: String,
    val channelRolePolicyCommandConsumerGroupId: String,
    val webhookLegacyConsumerGroupId: String = "cowork-preference-webhook-legacy-projection",
)

data class AppConfig(
    val serverPort: Int,
    val db: DbConfig,
    val redis: RedisConfig,
    val kafka: KafkaConfig,
    val webhookSecretEncryptionKey: String? = null,
    val webhookUrlSigningKey: String? = null,
) {
    companion object {
        fun from(json: JsonObject): AppConfig {
            val pref = json.getJsonObject("preference") ?: JsonObject()
            val db = pref.getJsonObject("db") ?: JsonObject()
            val redis = pref.getJsonObject("redis") ?: JsonObject()
            val kafka = pref.getJsonObject("kafka") ?: JsonObject()
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
                    teamMemberConsumerGroupId = kafka.getString(
                        "team-member-consumer-group-id",
                        "cowork-preference-team-member-projection",
                    ),
                    teamMemberTopic = kafka.getString("team-member-topic", "team.member.event"),
                    channelLifecycleConsumerGroupId = kafka.getString(
                        "channel-lifecycle-consumer-group-id",
                        "cowork-preference-channel-lifecycle-projection",
                    ),
                    channelTopic = kafka.getString("channel-topic", "channel.event.v2"),
                    projectLifecycleConsumerGroupId = kafka.getString(
                        "project-lifecycle-consumer-group-id",
                        "cowork-preference-project-lifecycle-projection",
                    ),
                    projectTopic = kafka.getString("project-topic", "project.event.v2"),
                    dmParticipantConsumerGroupId = kafka.getString(
                        "dm-participant-consumer-group-id",
                        "cowork-preference-dm-participant-projection",
                    ),
                    dmParticipantTopic = kafka.getString("dm-participant-topic", "channel.dm-participant.state.v1"),
                    teamRoleCommandConsumerGroupId = kafka.getString(
                        "team-role-command-consumer-group-id",
                        "cowork-preference-team-role-command",
                    ),
                    githubRepoSettingCommandConsumerGroupId = kafka.getString(
                        "github-repo-setting-command-consumer-group-id",
                        "cowork-preference-github-repo-setting-command",
                    ),
                    webhookLegacyConsumerGroupId = kafka.getString(
                        "webhook-legacy-consumer-group-id", "cowork-preference-webhook-legacy-projection",
                    ),
                    channelRolePolicyCommandConsumerGroupId = kafka.getString(
                        "channel-role-policy-command-consumer-group-id",
                        "cowork-preference-channel-role-policy-command",
                    ),
                ),
                webhookSecretEncryptionKey = System.getenv("WEBHOOK_SECRET_ENCRYPTION_KEY")?.takeIf(String::isNotBlank)
                    ?: pref.getJsonObject("webhook")?.getString("secret-encryption-key"),
                webhookUrlSigningKey = System.getenv("WEBHOOK_URL_SIGNING_KEY")
                    ?: (pref.getJsonObject("webhook")?.getValue("url-signing-key") as? String),
            )
        }

        private fun JsonObject.getInt(key: String, defaultValue: Int): Int = when (val value = getValue(key)) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull() ?: defaultValue
            else -> defaultValue
        }
    }
}
