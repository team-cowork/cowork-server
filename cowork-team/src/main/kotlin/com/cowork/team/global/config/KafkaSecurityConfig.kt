package com.cowork.team.global.config

import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.kafka.autoconfigure.KafkaProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment

@Configuration(proxyBeanMethods = false)
class KafkaSecurityConfig {
    companion object {
        @Bean
        @JvmStatic
        fun kafkaSaslProperties(environment: Environment): BeanPostProcessor = object : BeanPostProcessor {
            override fun postProcessBeforeInitialization(bean: Any, beanName: String): Any {
                if (bean is KafkaProperties) {
                    val protocol = environment.getProperty("KAFKA_SECURITY_PROTOCOL", "PLAINTEXT").uppercase()
                    require(protocol in setOf("PLAINTEXT", "SSL", "SASL_SSL")) {
                        "Use a supported Kafka security protocol"
                    }
                    bean.properties["security.protocol"] = protocol
                    if (protocol == "SASL_SSL") {
                        val mechanism = environment.getProperty("KAFKA_SASL_MECHANISM", "SCRAM-SHA-512").uppercase()
                        require(mechanism in setOf("SCRAM-SHA-256", "SCRAM-SHA-512")) {
                            "Use a SCRAM SASL mechanism"
                        }
                        val username = quote(environment.getRequiredProperty("KAFKA_SASL_USERNAME"))
                        val password = quote(environment.getRequiredProperty("KAFKA_SASL_PASSWORD"))
                        bean.properties["sasl.mechanism"] = mechanism
                        bean.properties["sasl.jaas.config"] =
                            "org.apache.kafka.common.security.scram.ScramLoginModule required " +
                            "username=\"$username\" password=\"$password\";"
                    }
                }
                return bean
            }
        }

        private fun quote(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")
    }
}
