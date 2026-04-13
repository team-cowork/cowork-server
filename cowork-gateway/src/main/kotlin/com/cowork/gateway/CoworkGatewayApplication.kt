package com.cowork.gateway

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class CoworkGatewayApplication

fun main(args: Array<String>) {
    runApplication<CoworkGatewayApplication>(*args)
}
