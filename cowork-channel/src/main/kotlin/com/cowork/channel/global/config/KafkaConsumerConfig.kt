package com.cowork.channel.global.config

import com.cowork.channel.global.projection.ProjectionAssignmentCoordinator
import com.cowork.channel.global.projection.ProjectionCheckpointStore
import com.cowork.channel.global.projection.ProjectionReadinessState
import com.cowork.channel.global.projection.ProjectionStream
import com.cowork.channel.global.projection.ProjectionStreams
import com.cowork.channel.global.projection.ProjectionTopicGenerationRegistry
import com.cowork.channel.global.projection.ProjectionTopicIdentityProvider
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.springframework.boot.kafka.autoconfigure.KafkaProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.annotation.EnableKafka
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.listener.DefaultErrorHandler

@Configuration
@EnableKafka
class KafkaConsumerConfig(
    private val kafkaProperties: KafkaProperties,
    private val checkpointStore: ProjectionCheckpointStore,
    private val readinessState: ProjectionReadinessState,
    private val streams: ProjectionStreams,
    private val topicIdentityProvider: ProjectionTopicIdentityProvider,
    private val topicGenerations: ProjectionTopicGenerationRegistry,
) {

    private fun consumerFactory(): ConsumerFactory<String, String> {
        val props = kafkaProperties.buildConsumerProperties().toMutableMap<String, Any>()
        props[ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG] = StringDeserializer::class.java
        props[ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG] = StringDeserializer::class.java
        return DefaultKafkaConsumerFactory<String, String>(props)
    }

    private fun listenerContainerFactory(
        stream: ProjectionStream,
    ): ConcurrentKafkaListenerContainerFactory<String, String> {
        val factory = ConcurrentKafkaListenerContainerFactory<String, String>()
        factory.setConsumerFactory(consumerFactory())
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

    @Bean
    fun projectEventListenerContainerFactory() = listenerContainerFactory(streams.project)

    @Bean
    fun teamMemberEventListenerContainerFactory() = listenerContainerFactory(streams.teamMember)

    @Bean
    fun teamLifecycleListenerContainerFactory() = listenerContainerFactory(streams.teamLifecycle)
}
