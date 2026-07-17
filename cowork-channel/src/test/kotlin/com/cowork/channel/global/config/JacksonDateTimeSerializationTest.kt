package com.cowork.channel.global.config

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDateTime

class JacksonDateTimeSerializationTest :
    StringSpec({
        "serializes LocalDateTime as an ISO-8601 string with the Boot 4 defaults" {
            ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration::class.java))
                .run { context ->
                    val jsonMapper = context.getBean(JsonMapper::class.java)
                    val value = LocalDateTime.of(2026, 7, 17, 13, 2, 3)

                    jsonMapper.writeValueAsString(value) shouldBe "\"2026-07-17T13:02:03\""
                }
        }
    })
