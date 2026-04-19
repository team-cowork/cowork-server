package com.cowork.preference

import io.vertx.core.Vertx
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("CoworkPreferenceMain")

fun main() {
    val vertx = Vertx.vertx()
    vertx.deployVerticle(MainVerticle()) { result ->
        if (result.succeeded()) {
            log.info("cowork-preference deployed. deploymentId={}", result.result())
        } else {
            log.error("cowork-preference deployment failed", result.cause())
            vertx.close()
        }
    }
}
