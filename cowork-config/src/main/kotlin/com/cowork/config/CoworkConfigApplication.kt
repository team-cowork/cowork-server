package com.cowork.config

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.cloud.config.server.EnableConfigServer
import org.springframework.cloud.netflix.eureka.server.EnableEurekaServer

@SpringBootApplication
@EnableConfigServer
@EnableEurekaServer
class CoworkConfigApplication

fun main(args: Array<String>) {
    runApplication<CoworkConfigApplication>(*args)
}
