package com.cowork.team.global.config

import com.cowork.team.global.consumer.TeamGithubConnectedPayload
import com.cowork.team.global.consumer.TeamGithubDisconnectedPayload
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.boot.kafka.autoconfigure.KafkaProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.annotation.EnableKafka
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.core.ProducerFactory
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer
import org.springframework.kafka.support.serializer.JacksonJsonSerializer
import org.springframework.util.backoff.FixedBackOff

@Configuration
@EnableKafka
class KafkaConsumerConfig(private val kafkaProperties: KafkaProperties) {

    companion object {
        private const val RETRY_INTERVAL_MS = 1_000L
        private const val RETRY_MAX_ATTEMPTS = 3L
    }

    // DeadLetterPublishingRecoverer용 범용 KafkaTemplate. 기존 KafkaConfig의 kafkaTemplate은
    // KafkaTemplate<String, TeamEventPayload>로 타입이 고정돼 있어 DLQ 발행용으로 재사용할 수 없다.
    @Bean
    fun teamGithubDlqProducerFactory(): ProducerFactory<String, Any> {
        val props = kafkaProperties.buildProducerProperties().toMutableMap<String, Any>()
        props[ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG] = StringSerializer::class.java
        props[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = JacksonJsonSerializer::class.java
        props[JacksonJsonSerializer.ADD_TYPE_INFO_HEADERS] = false
        return DefaultKafkaProducerFactory(props)
    }

    @Bean
    fun teamGithubDlqKafkaTemplate(): KafkaTemplate<String, Any> = KafkaTemplate(teamGithubDlqProducerFactory())

    private fun <T : Any> consumerFactory(targetType: Class<T>): ConsumerFactory<String, T> {
        val props = kafkaProperties.buildConsumerProperties().toMutableMap<String, Any>()
        props[ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG] = ErrorHandlingDeserializer::class.java
        props[ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG] = ErrorHandlingDeserializer::class.java
        props[ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS] = StringDeserializer::class.java
        props[ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS] = JacksonJsonDeserializer::class.java
        props[JacksonJsonDeserializer.TRUSTED_PACKAGES] = "*"
        props[JacksonJsonDeserializer.USE_TYPE_INFO_HEADERS] = false
        props[JacksonJsonDeserializer.VALUE_DEFAULT_TYPE] = targetType.name
        return DefaultKafkaConsumerFactory<String, T>(props)
    }

    private fun <T : Any> listenerContainerFactory(
        targetType: Class<T>,
    ): ConcurrentKafkaListenerContainerFactory<String, T> {
        val factory = ConcurrentKafkaListenerContainerFactory<String, T>()
        factory.setConsumerFactory(consumerFactory(targetType))
        factory.setCommonErrorHandler(
            DefaultErrorHandler(
                DeadLetterPublishingRecoverer(teamGithubDlqKafkaTemplate()),
                FixedBackOff(RETRY_INTERVAL_MS, RETRY_MAX_ATTEMPTS),
            ),
        )
        return factory
    }

    @Bean
    fun teamGithubConnectedListenerContainerFactory():
        ConcurrentKafkaListenerContainerFactory<String, TeamGithubConnectedPayload> =
        listenerContainerFactory(TeamGithubConnectedPayload::class.java)

    @Bean
    fun teamGithubDisconnectedListenerContainerFactory():
        ConcurrentKafkaListenerContainerFactory<String, TeamGithubDisconnectedPayload> =
        listenerContainerFactory(TeamGithubDisconnectedPayload::class.java)
}
