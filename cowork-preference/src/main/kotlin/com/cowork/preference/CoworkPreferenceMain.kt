package com.cowork.preference

import com.cowork.preference.config.AppConfig
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.vertx.core.DeploymentOptions
import io.vertx.core.Vertx
import io.vertx.core.VertxOptions
import io.vertx.core.json.JsonObject
import io.vertx.micrometer.Label
import io.vertx.micrometer.MicrometerMetricsFactory
import io.vertx.micrometer.MicrometerMetricsOptions
import io.vertx.micrometer.VertxPrometheusOptions
import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("CoworkPreferenceMain")

fun main() {
    System.setProperty("vertx.logger-delegate-factory-class-name", "io.vertx.core.logging.SLF4JLogDelegateFactory")
    val config = loadConfig()
    val appConfig = AppConfig.from(config)
    requireDbCredentials(appConfig)

    runFlyway(appConfig)

    val prometheusRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    MetricsRegistry.registry = prometheusRegistry

    val vertxOptions = VertxOptions().setMetricsOptions(
        MicrometerMetricsOptions()
            .addLabels(Label.HTTP_ROUTE)
            .setPrometheusOptions(VertxPrometheusOptions().setEnabled(true))
            .setEnabled(true),
    )
    val vertx = Vertx.builder()
        .with(vertxOptions)
        .withMetrics(MicrometerMetricsFactory(prometheusRegistry))
        .build()
    val options = DeploymentOptions().setConfig(config)
    vertx.deployVerticle(MainVerticle(), options).onComplete { result ->
        if (result.succeeded()) {
            log.info("cowork-preference deployed. deploymentId={}", result.result())
        } else {
            log.error("cowork-preference deployment failed", result.cause())
            vertx.close()
        }
    }
}

private fun requireDbCredentials(appConfig: AppConfig) {
    val missing = listOfNotNull(
        "preference.db.username".takeIf { appConfig.db.username.isBlank() },
        "preference.db.password".takeIf { appConfig.db.password.isBlank() },
    )
    if (missing.isNotEmpty()) {
        log.error(
            "Required config resolved to empty: {}. Check the injected DB credentials. Aborting startup.",
            missing.joinToString(", "),
        )
        System.exit(1)
    }
}

private fun loadConfig(): JsonObject {
    val poolSize = if (System.getenv("APP_CONFIG_PROFILE") == "prod") 10 else 5
    val config = JsonObject().put("preference", JsonObject().put("db", JsonObject().put("pool-size", poolSize)))
    return applyEnvironmentOverrides(config)
}

private fun applyEnvironmentOverrides(config: JsonObject): JsonObject {
    val mappings =
        mapOf(
            "PORT" to "server.port",
            "DB_HOST" to "preference.db.host",
            "DB_PORT" to "preference.db.port",
            "DB_NAME" to "preference.db.database",
            "DB_USERNAME" to "preference.db.username",
            "DB_PASSWORD" to "preference.db.password",
            "DB_SCHEMA" to "preference.db.schema",
            "DB_POOL_SIZE" to "preference.db.pool-size",
            "REDIS_HOST" to "preference.redis.host",
            "REDIS_PORT" to "preference.redis.port",
            "KAFKA_BOOTSTRAP_SERVERS" to "preference.kafka.bootstrap-servers",
            "KAFKA_GROUP_ID_TEAM_MEMBER_PROJECTION" to "preference.kafka.team-member-consumer-group-id",
            "KAFKA_TOPIC_TEAM_MEMBER_EVENT" to "preference.kafka.team-member-topic",
            "KAFKA_GROUP_ID_CHANNEL_LIFECYCLE_PROJECTION" to "preference.kafka.channel-lifecycle-consumer-group-id",
            "KAFKA_TOPIC_CHANNEL_EVENT" to "preference.kafka.channel-topic",
            "KAFKA_GROUP_ID_PROJECT_LIFECYCLE_PROJECTION" to "preference.kafka.project-lifecycle-consumer-group-id",
            "KAFKA_TOPIC_PROJECT_EVENT" to "preference.kafka.project-topic",
            "KAFKA_GROUP_ID_DM_PARTICIPANT_PROJECTION" to "preference.kafka.dm-participant-consumer-group-id",
            "KAFKA_TOPIC_DM_PARTICIPANT_STATE" to "preference.kafka.dm-participant-topic",
            "KAFKA_GROUP_ID_WEBHOOK_LEGACY_PROJECTION" to "preference.kafka.webhook-legacy-consumer-group-id",
            "KAFKA_GROUP_ID_TEAM_ROLE_COMMAND" to "preference.kafka.team-role-command-consumer-group-id",
            "KAFKA_GROUP_ID_GITHUB_REPO_SETTING_COMMAND" to
                "preference.kafka.github-repo-setting-command-consumer-group-id",
            "KAFKA_GROUP_ID_CHANNEL_ROLE_POLICY_COMMAND" to
                "preference.kafka.channel-role-policy-command-consumer-group-id",
        )

    mappings.forEach { (environmentKey, configKey) ->
        System.getenv(environmentKey)?.takeIf(String::isNotBlank)?.let { value ->
            setNested(config, configKey, value)
        }
    }
    return config
}

private fun setNested(root: JsonObject, dotKey: String, value: Any?) {
    val parts = dotKey.split(".")
    var node = root
    for (i in 0 until parts.size - 1) {
        val part = parts[i]
        if (node.getValue(part) !is JsonObject) node.put(part, JsonObject())
        node = node.getJsonObject(part)
    }
    node.put(parts.last(), value)
}

private fun runFlyway(config: AppConfig) {
    val jdbcUrl = "jdbc:postgresql://${config.db.host}:${config.db.port}/${config.db.database}"
    log.info("Running Flyway migration on {}", jdbcUrl)
    Flyway.configure()
        .dataSource(jdbcUrl, config.db.username, config.db.password)
        .schemas(config.db.schema)
        .locations("classpath:db/migration")
        .load()
        .migrate()
    log.info("Flyway migration completed")
}
