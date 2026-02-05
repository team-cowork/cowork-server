package com.cowork.global.config

import com.fasterxml.jackson.databind.DeserializationFeature
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class JacksonConfig {

    /**
     * ID나 boolean 값을 빠뜨린 요청이 0이나 false로 처리되지 않도록 null과 누락을 오류로 본다.
     */
    @Bean
    fun strictPrimitiveCustomizer(): Jackson2ObjectMapperBuilderCustomizer =
        Jackson2ObjectMapperBuilderCustomizer { builder ->
            builder.featuresToEnable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        }
}
