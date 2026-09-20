package com.cowork.gateway

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class CoworkGatewayApplication {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            runApplication<CoworkGatewayApplication>(*args)
        }
    }
}
