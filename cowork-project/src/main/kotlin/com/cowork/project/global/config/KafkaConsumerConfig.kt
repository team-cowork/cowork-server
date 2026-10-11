package com.cowork.project.global.config

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
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.kafka.listener.DefaultErrorHandler
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

    private fun durableResultListenerContainerFactory(): ConcurrentKafkaListenerContainerFactory<String, String> {
        val props = kafkaProperties.buildConsumerProperties().toMutableMap<String, Any>()
        props[ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG] = StringDeserializer::class.java
        props[ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG] = StringDeserializer::class.java
        val factory = ConcurrentKafkaListenerContainerFactory<String, String>()
        factory.setConsumerFactory(DefaultKafkaConsumerFactory<String, String>(props))
        factory.setCommonErrorHandler(errorHandler(KafkaConsumerRetryPolicy.stateProjectionBackOff()))
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
    fun channelStateListenerContainerFactory() = projectionListenerContainerFactory(streams.channelState)

    @Bean
    fun teamLifecycleListenerContainerFactory() = projectionListenerContainerFactory(streams.teamLifecycle)

    @Bean
    fun teamMemberEventListenerContainerFactory() = projectionListenerContainerFactory(streams.teamMember)

    @Bean
    fun userProfileListenerContainerFactory() = projectionListenerContainerFactory(streams.userProfile)

    @Bean
    fun githubRepoSettingStateListenerContainerFactory() = projectionListenerContainerFactory(streams.githubRepoSetting)

    @Bean
    fun githubRepoSettingResultListenerContainerFactory() = durableResultListenerContainerFactory()

    @Bean
    fun chatGithubIssueCommandListenerContainerFactory() = durableResultListenerContainerFactory()

    @Bean
    fun githubIssueWriteResultListenerContainerFactory() = durableResultListenerContainerFactory()
}
