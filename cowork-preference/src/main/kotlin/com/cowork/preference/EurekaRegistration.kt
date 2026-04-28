package com.cowork.preference

import com.cowork.preference.config.AppConfig
import io.vertx.core.json.JsonObject
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class EurekaRegistration(private val config: AppConfig) {
    private val log = LoggerFactory.getLogger(EurekaRegistration::class.java)
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()
    private val serverUrl = config.eurekaUrl.trimEnd('/')

    fun register() {
        if (!config.eurekaEnabled) return

        val payload = JsonObject()
            .put(
                "instance",
                JsonObject()
                    .put("instanceId", config.eurekaInstanceId)
                    .put("hostName", config.eurekaInstanceHost)
                    .put("app", config.eurekaAppName.uppercase())
                    .put("ipAddr", config.eurekaInstanceHost)
                    .put("vipAddress", config.eurekaAppName)
                    .put("secureVipAddress", config.eurekaAppName)
                    .put("status", "UP")
                    .put("port", JsonObject().put("\$", config.serverPort).put("@enabled", "true"))
                    .put("securePort", JsonObject().put("\$", 443).put("@enabled", "false"))
                    .put("healthCheckUrl", "http://${config.eurekaInstanceHost}:${config.serverPort}/health")
                    .put("statusPageUrl", "http://${config.eurekaInstanceHost}:${config.serverPort}/health")
                    .put("homePageUrl", "http://${config.eurekaInstanceHost}:${config.serverPort}/")
                    .put(
                        "dataCenterInfo",
                        JsonObject()
                            .put("@class", "com.netflix.appinfo.InstanceInfo\$DefaultDataCenterInfo")
                            .put("name", "MyOwn"),
                    )
                    .put(
                        "metadata",
                        JsonObject()
                            .put("management.port", config.serverPort.toString())
                            .put("prometheus.scrape", "true")
                            .put("prometheus.path", "/metrics"),
                    ),
            )

        val request = HttpRequest.newBuilder()
            .uri(URI.create("$serverUrl/apps/${config.eurekaAppName}"))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(5))
            .POST(HttpRequest.BodyPublishers.ofString(payload.encode()))
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.discarding())
        if (response.statusCode() !in 200..299) {
            error("Eureka register failed: status=${response.statusCode()}")
        }
        log.info("registered with Eureka as {}", config.eurekaInstanceId)
    }

    fun heartbeat() {
        if (!config.eurekaEnabled) return

        val request = HttpRequest.newBuilder()
            .uri(URI.create(instanceUrl()))
            .timeout(Duration.ofSeconds(5))
            .PUT(HttpRequest.BodyPublishers.noBody())
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.discarding())
        if (response.statusCode() == 404) {
            register()
            return
        }
        if (response.statusCode() !in 200..299) {
            error("Eureka heartbeat failed: status=${response.statusCode()}")
        }
    }

    fun deregister() {
        if (!config.eurekaEnabled) return

        val request = HttpRequest.newBuilder()
            .uri(URI.create(instanceUrl()))
            .timeout(Duration.ofSeconds(5))
            .DELETE()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.discarding())
        if (response.statusCode() !in 200..299) {
            error("Eureka deregister failed: status=${response.statusCode()}")
        }
    }

    private fun instanceUrl(): String = "$serverUrl/apps/${config.eurekaAppName}/${config.eurekaInstanceId}"
}
