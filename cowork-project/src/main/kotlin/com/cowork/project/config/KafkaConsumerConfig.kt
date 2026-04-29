package com.cowork.project.config

import com.cowork.project.consumer.TeamLifecyclePayload
import com.cowork.project.consumer.UserLifecyclePayload
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.springframework.boot.autoconfigure.kafka.KafkaProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.annotation.EnableKafka
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer
import org.springframework.kafka.support.serializer.JsonDeserializer
import org.springframework.util.backoff.FixedBackOff

@Configuration
@EnableKafka
class KafkaConsumerConfig(
    private val kafkaProperties: KafkaProperties,
    private val kafkaTemplate: KafkaTemplate<String, Any>,
) {

    private fun <T> consumerFactory(targetType: Class<T>): ConsumerFactory<String, T> {
        val props = kafkaProperties.buildConsumerProperties(null).toMutableMap<String, Any>()
        props[ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG] = ErrorHandlingDeserializer::class.java
        props[ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG] = ErrorHandlingDeserializer::class.java
        props[ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS] = StringDeserializer::class.java
        props[ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS] = JsonDeserializer::class.java
        props[JsonDeserializer.TRUSTED_PACKAGES] = "*"
        props[JsonDeserializer.USE_TYPE_INFO_HEADERS] = false
        props[JsonDeserializer.VALUE_DEFAULT_TYPE] = targetType.name
        return DefaultKafkaConsumerFactory(props)
    }

    private fun <T> listenerContainerFactory(
        targetType: Class<T>,
    ): ConcurrentKafkaListenerContainerFactory<String, T> {
        val factory = ConcurrentKafkaListenerContainerFactory<String, T>()
        factory.consumerFactory = consumerFactory(targetType)
        factory.setCommonErrorHandler(
            DefaultErrorHandler(
                DeadLetterPublishingRecoverer(kafkaTemplate),
                FixedBackOff(1_000L, 3L),
            )
        )
        return factory
    }

    @Bean
    fun teamLifecycleListenerContainerFactory(): ConcurrentKafkaListenerContainerFactory<String, TeamLifecyclePayload> =
        listenerContainerFactory(TeamLifecyclePayload::class.java)

    @Bean
    fun userLifecycleListenerContainerFactory(): ConcurrentKafkaListenerContainerFactory<String, UserLifecyclePayload> =
        listenerContainerFactory(UserLifecyclePayload::class.java)
}
