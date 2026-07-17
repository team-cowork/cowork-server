package com.cowork.channel.global.config

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.web.client.RestClient

class RestClientAutoConfigurationTest : StringSpec({
    "provides the RestClient builder required by OAuth callbacks" {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RestClientAutoConfiguration::class.java))
            .run { context ->
                context.getBeansOfType(RestClient.Builder::class.java).values.shouldHaveSize(1)
            }
    }
})
