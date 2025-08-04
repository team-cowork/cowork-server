package com.cowork

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class CoworkApplication

fun main(args: Array<String>) {
    runApplication<CoworkApplication>(*args)
}
