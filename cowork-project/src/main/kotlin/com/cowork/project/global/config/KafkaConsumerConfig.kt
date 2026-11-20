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
import org.springframework.beans.factory.annotation.Value
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
    private val projectionCheckpointStore: ProjectionCheckpointStore,
    private val projectionReadinessState: ProjectionReadinessState,
    private val projectionStreams: ProjectionStreams,
    private val projectionTopicIdentityProvider: ProjectionTopicIdentityProvider,
    private val projectionTopicGenerationRegistry: ProjectionTopicGenerationRegistry,
    @Value("\${projection.readiness.assignment-retry-backoff-ms:1000}") private val assignmentRetryBackoffMs: Long,
    @Value("\${projection.readiness.assignment-retry-timeout-ms:60000}") private val assignmentRetryTimeoutMs: Long,
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
                projectionCheckpointStore,
                projectionReadinessState,
                projectionTopicIdentityProvider,
                projectionTopicGenerationRegistry,
                assignmentRetryBackoffMs,
                assignmentRetryTimeoutMs,
            ),
        )
        return factory
    }

    @Bean
    fun durableListenerContainerFactory(): ConcurrentKafkaListenerContainerFactory<String, String> {
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
    fun preferenceTeamRoleChangedListenerContainerFactory() =
        projectionListenerContainerFactory(projectionStreams.teamRole)

    @Bean
    fun projectAccessListenerContainerFactory() = projectionListenerContainerFactory(projectionStreams.projectAccess)

    @Bean
    fun channelStateListenerContainerFactory() = projectionListenerContainerFactory(projectionStreams.channelState)

    @Bean
    fun teamLifecycleListenerContainerFactory() = projectionListenerContainerFactory(projectionStreams.teamLifecycle)

    @Bean
    fun teamMemberEventListenerContainerFactory() = projectionListenerContainerFactory(projectionStreams.teamMember)

    @Bean
    fun userProfileListenerContainerFactory() = projectionListenerContainerFactory(projectionStreams.userProfile)

    @Bean
    fun githubRepoSettingStateListenerContainerFactory() =
        projectionListenerContainerFactory(projectionStreams.githubRepoSetting)

    @Bean
    fun githubRepoStateListenerContainerFactory() =
        projectionListenerContainerFactory(projectionStreams.githubRepoState)

    @Bean
    fun githubIssueStateListenerContainerFactory() =
        projectionListenerContainerFactory(projectionStreams.githubIssueState)

    @Bean
    fun githubPullRequestStateListenerContainerFactory() =
        projectionListenerContainerFactory(projectionStreams.githubPullRequestState)

    @Bean
    fun githubLabelStateListenerContainerFactory() =
        projectionListenerContainerFactory(projectionStreams.githubLabelState)

    @Bean
    fun githubIssueCommentStateListenerContainerFactory() =
        projectionListenerContainerFactory(projectionStreams.githubIssueCommentState)

    @Bean
    fun githubProjectStateListenerContainerFactory() =
        projectionListenerContainerFactory(projectionStreams.githubProjectState)

    @Bean
    fun githubProjectItemStateListenerContainerFactory() =
        projectionListenerContainerFactory(projectionStreams.githubProjectItemState)
}
