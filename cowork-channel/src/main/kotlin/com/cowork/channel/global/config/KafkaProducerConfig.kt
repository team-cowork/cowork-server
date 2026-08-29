package com.cowork.channel.global.config

import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.boot.kafka.autoconfigure.KafkaProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.core.ProducerFactory
import org.springframework.kafka.support.serializer.JacksonJsonSerializer

internal const val CHANNEL_ROLE_POLICY_RESULT_DLT_TEMPLATE_BEAN = "channelRolePolicyResultDltKafkaTemplate"

@Configuration
class KafkaProducerConfig(private val kafkaProperties: KafkaProperties) {

    @Bean
    fun producerFactory(): ProducerFactory<String, Any> {
        val props = kafkaProperties.buildProducerProperties().toMutableMap<String, Any>()
        props[ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java
        props[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = JacksonJsonSerializer::class.java
        props[JacksonJsonSerializer.ADD_TYPE_INFO_HEADERS] = false
        return DefaultKafkaProducerFactory(props)
    }

    @Bean
    fun kafkaTemplate(): KafkaTemplate<String, Any> = KafkaTemplate(producerFactory())

    @Bean
    fun channelRolePolicyResultDltProducerFactory(): ProducerFactory<String, String> {
        val props = kafkaProperties.buildProducerProperties().toMutableMap<String, Any>()
        props[ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java
        props[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java
        return DefaultKafkaProducerFactory(props)
    }

    @Bean(name = [CHANNEL_ROLE_POLICY_RESULT_DLT_TEMPLATE_BEAN])
    fun channelRolePolicyResultDltKafkaTemplate(): KafkaTemplate<String, String> =
        KafkaTemplate(channelRolePolicyResultDltProducerFactory())
}
