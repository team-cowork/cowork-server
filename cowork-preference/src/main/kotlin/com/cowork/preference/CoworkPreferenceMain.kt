package com.cowork.preference

import com.cowork.preference.config.AppConfig
import io.micrometer.prometheus.PrometheusConfig
import io.micrometer.prometheus.PrometheusMeterRegistry
import io.vertx.core.DeploymentOptions
import io.vertx.core.Vertx
import io.vertx.core.VertxOptions
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.micrometer.MicrometerMetricsOptions
import io.vertx.micrometer.VertxPrometheusOptions
import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

private val log = LoggerFactory.getLogger("CoworkPreferenceMain")
private val PLACEHOLDER_REGEX = Regex("""\$\{([^:}]+)(?::([^}]*))?\}""")

fun main() {
    System.setProperty("vertx.logger-delegate-factory-class-name", "io.vertx.core.logging.SLF4JLogDelegateFactory")
    val config = loadConfig()
    val appConfig = AppConfig.from(config)

    runFlyway(appConfig)

    val eurekaRegistrar = EurekaRegistrar(
        eurekaUrl = appConfig.eurekaUrl,
        appName = appConfig.eurekaAppName,
        instanceHost = appConfig.eurekaInstanceHost,
        port = appConfig.serverPort,
    )
    try {
        eurekaRegistrar.register()
    } catch (e: Exception) {
        log.error("Critical: Eureka registration failed", e)
        System.exit(1)
    }
    eurekaRegistrar.startHeartbeat()

    Runtime.getRuntime().addShutdownHook(Thread {
        eurekaRegistrar.deregister()
    })

    val prometheusRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    MetricsRegistry.registry = prometheusRegistry

    val vertxOptions = VertxOptions().setMetricsOptions(
        MicrometerMetricsOptions()
            .setPrometheusOptions(VertxPrometheusOptions().setEnabled(true))
            .setMicrometerRegistry(prometheusRegistry)
            .setEnabled(true)
    )
    val vertx = Vertx.vertx(vertxOptions)
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
    val configServerUrl = System.getenv("CONFIG_SERVER_URL") ?: "http://localhost:8761"
    val profile = System.getenv("SPRING_PROFILES_ACTIVE") ?: "local"

    log.info("Fetching config from {} profile={}", configServerUrl, profile)
    val serverConfig = fetchFromConfigServer(configServerUrl, profile)
    return resolveJsonObject(serverConfig)
}

private fun fetchFromConfigServer(baseUrl: String, profile: String): JsonObject {
    val url = "$baseUrl/cowork-preference/$profile"
    val client = HttpClient.newHttpClient()
    val request = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .header("Accept", "application/json")
        .timeout(Duration.ofSeconds(5))
        .build()

    repeat(3) { attempt ->
        try {
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 200) return parseSpringConfig(JsonObject(response.body()))
            log.warn("Config server returned HTTP {} (attempt {})", response.statusCode(), attempt + 1)
        } catch (e: Exception) {
            log.warn("Config server unreachable (attempt {}): {}", attempt + 1, e.message)
        }
        if (attempt < 2) Thread.sleep(2_000)
    }

    log.error("Config server unreachable after 3 attempts. Aborting startup.")
    System.exit(1)
    error("unreachable")
}

private fun parseSpringConfig(json: JsonObject): JsonObject {
    val result = JsonObject()
    val sources = json.getJsonArray("propertySources") ?: return result
    // 낮은 우선순위부터 역순으로 처리하여 높은 우선순위가 덮어쓰도록
    for (i in sources.size() - 1 downTo 0) {
        val source = (sources.getValue(i) as? JsonObject)?.getJsonObject("source") ?: continue
        source.fieldNames().forEach { key -> setNested(result, key, source.getValue(key)) }
    }
    return result
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

private fun resolveJsonObject(obj: JsonObject): JsonObject {
    val resolved = JsonObject()
    obj.fieldNames().forEach { key -> resolved.put(key, resolveValue(obj.getValue(key))) }
    return resolved
}

private fun resolveValue(value: Any?): Any? = when (value) {
    is String -> PLACEHOLDER_REGEX.replace(value) { match ->
        System.getenv(match.groupValues[1]) ?: match.groupValues[2]
    }
    is JsonObject -> resolveJsonObject(value)
    is JsonArray -> JsonArray(value.map { resolveValue(it) })
    else -> value
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
