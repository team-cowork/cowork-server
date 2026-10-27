package com.cowork.roadmap.global.config;

import java.util.Set;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
public class KafkaSecurityConfig {
    @Bean
    public static BeanPostProcessor kafkaSaslProperties(Environment environment) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String beanName) {
                if (bean instanceof KafkaProperties properties
                        && environment.getProperty("KAFKA_SECURITY_PROTOCOL", "PLAINTEXT").startsWith("SASL_")) {
                    String mechanism = environment.getProperty("KAFKA_SASL_MECHANISM", "SCRAM-SHA-512");
                    if (!Set.of("SCRAM-SHA-256", "SCRAM-SHA-512").contains(mechanism)) {
                        throw new IllegalArgumentException("Use a SCRAM SASL mechanism");
                    }
                    String username = quote(environment.getRequiredProperty("KAFKA_SASL_USERNAME"));
                    String password = quote(environment.getRequiredProperty("KAFKA_SASL_PASSWORD"));
                    properties.getProperties()
                            .put("sasl.jaas.config",
                                    "org.apache.kafka.common.security.scram.ScramLoginModule required " + "username=\""
                                            + username + "\" password=\"" + password + "\";");
                }
                return bean;
            }
        };
    }

    private static String quote(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
