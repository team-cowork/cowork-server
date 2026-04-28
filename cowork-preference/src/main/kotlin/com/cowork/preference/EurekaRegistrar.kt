package com.cowork.preference

import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

private const val HEARTBEAT_INTERVAL_SECONDS = 30L

class EurekaRegistrar(
    private val eurekaUrl: String,
    private val appName: String,
    private val instanceHost: String,
    private val port: Int,
) {
    private val log = LoggerFactory.getLogger(EurekaRegistrar::class.java)
    private val client = HttpClient.newHttpClient()
    private val instanceId = "$instanceHost:$appName:$port"
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "eureka-heartbeat").also { it.isDaemon = true }
    }
    private var heartbeatFuture: ScheduledFuture<*>? = null

    fun register() {
        val url = "${eurekaUrl.trimEnd('/')}/apps/$appName"
        val body = buildRegistrationJson()
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .timeout(Duration.ofSeconds(5))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() in 200..204) {
            log.info("Registered with Eureka: app={} instance={}", appName, instanceId)
        } else {
            throw IllegalStateException("Eureka registration returned HTTP ${response.statusCode()}: ${response.body()}")
        }
    }

    fun startHeartbeat() {
        heartbeatFuture = scheduler.scheduleAtFixedRate(::sendHeartbeat, HEARTBEAT_INTERVAL_SECONDS, HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS)
    }

    fun deregister() {
        heartbeatFuture?.cancel(false)
        scheduler.shutdownNow()
        val url = "${eurekaUrl.trimEnd('/')}/apps/$appName/$instanceId"
        try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .DELETE()
                .build()
            client.send(request, HttpResponse.BodyHandlers.discarding())
            log.info("Deregistered from Eureka: instance={}", instanceId)
        } catch (e: Exception) {
            log.warn("Eureka deregister failed: {}", e.message)
        }
    }

    private fun sendHeartbeat() {
        val url = "${eurekaUrl.trimEnd('/')}/apps/$appName/$instanceId"
        try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .PUT(HttpRequest.BodyPublishers.noBody())
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.discarding())
            if (response.statusCode() != 200) {
                log.warn("Eureka heartbeat returned HTTP {}, re-registering", response.statusCode())
                register()
            }
        } catch (e: Exception) {
            log.warn("Eureka heartbeat failed: {}", e.message)
            try { register() } catch (re: Exception) {
                log.warn("Eureka re-registration failed: {}", re.message)
            }
        }
    }

    private fun buildRegistrationJson(): String = """
        {
          "instance": {
            "instanceId": "$instanceId",
            "hostName": "$instanceHost",
            "app": "${appName.uppercase()}",
            "ipAddr": "$instanceHost",
            "vipAddress": "$appName",
            "secureVipAddress": "$appName",
            "status": "UP",
            "port": {"${'$'}": $port, "@enabled": "true"},
            "securePort": {"${'$'}": 443, "@enabled": "false"},
            "healthCheckUrl": "http://$instanceHost:$port/health",
            "statusPageUrl": "http://$instanceHost:$port/health",
            "homePageUrl": "http://$instanceHost:$port/",
            "dataCenterInfo": {
              "@class": "com.netflix.appinfo.InstanceInfo${'$'}DefaultDataCenterInfo",
              "name": "MyOwn"
            }
          }
        }
    """.trimIndent()
}
