package com.cowork.project

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.cloud.openfeign.EnableFeignClients

@SpringBootApplication
@EnableFeignClients
class CoworkProjectApplication

fun main(args: Array<String>) {
    runApplication<CoworkProjectApplication>(*args)
}
