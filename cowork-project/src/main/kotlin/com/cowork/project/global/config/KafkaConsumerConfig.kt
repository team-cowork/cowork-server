package com.cowork.project.global.config

import com.cowork.project.global.consumer.TeamGithubConnectedPayload
import com.cowork.project.global.consumer.TeamGithubDisconnectedPayload
import com.cowork.project.global.projection.ProjectionAssignmentCoordinator
import com.cowork.project.global.projection.ProjectionCheckpointStore
import com.cowork.project.global.projection.ProjectionReadinessState
import com.cowork.project.global.projection.ProjectionStream
import com.cowork.project.global.projection.ProjectionStreams
import com.cowork.project.global.projection.ProjectionTopicGenerationRegistry
import com.cowork.project.global.projection.ProjectionTopicIdentityProvider
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringDeserializer
import org.springframework.boot.kafka.autoconfigure.KafkaProperties
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
import org.springframework.util.backoff.BackOff

@Configuration
@EnableKafka
class KafkaConsumerConfig(
    private val kafkaProperties: KafkaProperties,
    private val kafkaTemplate: KafkaTemplate<String, Any>,
    private val checkpointStore: ProjectionCheckpointStore,
    private val readinessState: ProjectionReadinessState,
    private val streams: ProjectionStreams,
    private val topicIdentityProvider: ProjectionTopicIdentityProvider,
    private val topicGenerations: ProjectionTopicGenerationRegistry,
) {

    private fun <T : Any> consumerFactory(targetType: Class<T>): ConsumerFactory<String, T> {
        val props = kafkaProperties.buildConsumerProperties().toMutableMap<String, Any>()
        props[ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG] = ErrorHandlingDeserializer::class.java
        props[ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG] = ErrorHandlingDeserializer::class.java
        props[ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS] = StringDeserializer::class.java
        props[ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS] = JsonDeserializer::class.java
        props[JsonDeserializer.TRUSTED_PACKAGES] = "*"
        props[JsonDeserializer.USE_TYPE_INFO_HEADERS] = false
        props[JsonDeserializer.VALUE_DEFAULT_TYPE] = targetType.name
        return DefaultKafkaConsumerFactory<String, T>(props)
    }

    private fun <T : Any> listenerContainerFactory(
        targetType: Class<T>,
        backOff: BackOff,
    ): ConcurrentKafkaListenerContainerFactory<String, T> {
        val factory = ConcurrentKafkaListenerContainerFactory<String, T>()
        factory.setConsumerFactory(consumerFactory(targetType))
        factory.setCommonErrorHandler(errorHandler(backOff))
        return factory
    }

    private fun projectionListenerContainerFactory(
        stream: ProjectionStream,
    ): ConcurrentKafkaListenerContainerFactory<String, String> {
        val props = kafkaProperties.buildConsumerProperties().toMutableMap<String, Any>()
        props[ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG] = StringDeserializer::class.java
        props[ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG] = StringDeserializer::class.java
        val factory = ConcurrentKafkaListenerContainerFactory<String, String>()
        factory.setConsumerFactory(DefaultKafkaConsumerFactory<String, String>(props))
        factory.setCommonErrorHandler(DefaultErrorHandler(KafkaConsumerRetryPolicy.stateProjectionBackOff()))
        factory.containerProperties.setConsumerRebalanceListener(
            ProjectionAssignmentCoordinator(
                stream,
                checkpointStore,
                readinessState,
                topicIdentityProvider,
                topicGenerations,
            ),
        )
        return factory
    }

    private fun errorHandler(backOff: BackOff): DefaultErrorHandler {
        val recoverer = DeadLetterPublishingRecoverer(kafkaTemplate) { record, _ ->
            TopicPartition(KafkaConsumerRetryPolicy.deadLetterTopic(record.topic()), record.partition())
        }
        recoverer.setFailIfSendResultIsError(true)
        return DefaultErrorHandler(recoverer, backOff).apply {
            addNotRetryableExceptions(IllegalArgumentException::class.java)
        }
    }

    @Bean
    fun teamLifecycleListenerContainerFactory() = projectionListenerContainerFactory(streams.teamLifecycle)

    @Bean
    fun teamMemberEventListenerContainerFactory() = projectionListenerContainerFactory(streams.teamMember)

    @Bean
    fun userLifecycleListenerContainerFactory() = projectionListenerContainerFactory(streams.userLifecycle)

    @Bean
    fun teamGithubConnectedListenerContainerFactory():
        ConcurrentKafkaListenerContainerFactory<String, TeamGithubConnectedPayload> =
        listenerContainerFactory(
            TeamGithubConnectedPayload::class.java,
            KafkaConsumerRetryPolicy.externalEventBackOff(),
        )

    @Bean
    fun teamGithubDisconnectedListenerContainerFactory():
        ConcurrentKafkaListenerContainerFactory<String, TeamGithubDisconnectedPayload> =
        listenerContainerFactory(
            TeamGithubDisconnectedPayload::class.java,
            KafkaConsumerRetryPolicy.externalEventBackOff(),
        )
}
