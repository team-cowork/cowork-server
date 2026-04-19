package com.cowork.preference

import com.cowork.preference.config.AppConfig
import io.vertx.core.DeploymentOptions
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("CoworkPreferenceMain")

fun main() {
    val config = loadConfig()
    val appConfig = AppConfig.from(config)

    runFlyway(appConfig)

    val vertx = Vertx.vertx()
    val options = DeploymentOptions().setConfig(config)
    vertx.deployVerticle(MainVerticle(), options) { result ->
        if (result.succeeded()) {
            log.info("cowork-preference deployed. deploymentId={}", result.result())
        } else {
            log.error("cowork-preference deployment failed", result.cause())
            vertx.close()
        }
    }
}

private fun loadConfig(): JsonObject {
    val host = System.getenv("POSTGRES_HOST") ?: "localhost"
    val username = System.getenv("POSTGRES_USER") ?: "cowork"
    val password = System.getenv("POSTGRES_PASSWORD") ?: ""
    val redisHost = System.getenv("REDIS_HOST") ?: "localhost"
    val redisPort = System.getenv("REDIS_PORT")?.toIntOrNull() ?: 6379
    val kafkaBootstrap = System.getenv("KAFKA_BOOTSTRAP_SERVERS") ?: "localhost:9094"
    val eurekaUrl = System.getenv("EUREKA_URL") ?: "http://localhost:8761/eureka/"
    val serverPort = System.getenv("SERVER_PORT")?.toIntOrNull() ?: 8085

    return JsonObject()
        .put("server", JsonObject().put("port", serverPort))
        .put("preference", JsonObject()
            .put("db", JsonObject()
                .put("host", host)
                .put("port", 5432)
                .put("database", "cowork_preference")
                .put("schema", "preference")
                .put("username", username)
                .put("password", password)
                .put("pool-size", 5)
            )
            .put("redis", JsonObject()
                .put("host", redisHost)
                .put("port", redisPort)
            )
            .put("kafka", JsonObject()
                .put("bootstrap-servers", kafkaBootstrap)
            )
        )
        .put("eureka.url", eurekaUrl)
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
