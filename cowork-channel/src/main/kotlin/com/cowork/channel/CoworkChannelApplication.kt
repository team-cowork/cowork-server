package com.cowork.channel

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class CoworkChannelApplication

fun main(args: Array<String>) {
    runApplication<CoworkChannelApplication>(*args)
}

