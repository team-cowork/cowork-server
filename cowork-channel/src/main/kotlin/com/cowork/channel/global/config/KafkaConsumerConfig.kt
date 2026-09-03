package com.cowork.channel.global.config

import com.cowork.channel.global.consumer.Topics
import com.cowork.channel.global.projection.ProjectionAssignmentCoordinator
import com.cowork.channel.global.projection.ProjectionCheckpointStore
import com.cowork.channel.global.projection.ProjectionReadinessState
import com.cowork.channel.global.projection.ProjectionStream
import com.cowork.channel.global.projection.ProjectionStreams
import com.cowork.channel.global.projection.ProjectionTopicGenerationRegistry
import com.cowork.channel.global.projection.ProjectionTopicIdentityProvider
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.header.Headers
import org.apache.kafka.common.serialization.StringDeserializer
import org.springframework.beans.factory.annotation.Qualifier
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
import java.nio.charset.StandardCharsets

@Configuration
@EnableKafka
class KafkaConsumerConfig(
    private val kafkaProperties: KafkaProperties,
    @Qualifier(CHANNEL_ROLE_POLICY_RESULT_DLT_TEMPLATE_BEAN)
    private val channelRolePolicyResultDltKafkaTemplate: KafkaTemplate<String, String>,
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

    @Bean
    fun preferenceTeamRoleChangedListenerContainerFactory() = listenerContainerFactory(streams.teamRole)

    @Bean
    fun channelRolePolicyChangedListenerContainerFactory() = listenerContainerFactory(streams.channelRolePolicy)

    @Bean
    fun channelRolePolicyCommandResultListenerContainerFactory() =
        ConcurrentKafkaListenerContainerFactory<String, String>().apply {
            setConsumerFactory(consumerFactory())
            setCommonErrorHandler(
                ChannelRolePolicyCommandResultErrorHandler.create(channelRolePolicyResultDltKafkaTemplate),
            )
        }
}

internal object ChannelRolePolicyCommandResultErrorHandler {
    fun create(kafkaTemplate: KafkaTemplate<String, String>): DefaultErrorHandler = DefaultErrorHandler(
        deadLetterRecoverer(kafkaTemplate),
        KafkaConsumerRetryPolicy.commandResultBackOff(),
    ).apply {
        addNotRetryableExceptions(IllegalArgumentException::class.java)
    }

    fun deadLetterRecoverer(kafkaTemplate: KafkaTemplate<String, String>): DeadLetterPublishingRecoverer =
        DeadLetterPublishingRecoverer(kafkaTemplate) { record, _ ->
            require(record.topic() == Topics.CHANNEL_ROLE_POLICY_COMMAND_RESULT) {
                "channel role policy result DLT recoverer가 예상하지 않은 topic을 수신했습니다."
            }
            TopicPartition(Topics.CHANNEL_ROLE_POLICY_COMMAND_RESULT_DLT, record.partition())
        }.apply {
            setFailIfSendResultIsError(true)
            setExceptionHeadersCreator(SafeExceptionHeadersCreator)
        }

    private object SafeExceptionHeadersCreator : DeadLetterPublishingRecoverer.ExceptionHeadersCreator {
        override fun create(
            kafkaHeaders: Headers,
            exception: Exception,
            isKey: Boolean,
            headerNames: DeadLetterPublishingRecoverer.HeaderNames,
        ) {
            val exceptionInfo = headerNames.exceptionInfo
            listOf(
                exceptionInfo.keyExceptionFqcn,
                exceptionInfo.exceptionFqcn,
                exceptionInfo.exceptionCauseFqcn,
                exceptionInfo.keyExceptionMessage,
                exceptionInfo.exceptionMessage,
                exceptionInfo.keyExceptionStacktrace,
                exceptionInfo.exceptionStacktrace,
            ).forEach(kafkaHeaders::remove)
            val exceptionHeader = if (isKey) exceptionInfo.keyExceptionFqcn else exceptionInfo.exceptionFqcn
            kafkaHeaders.add(exceptionHeader, exception.javaClass.name.toByteArray(StandardCharsets.UTF_8))
            exception.cause?.let { cause ->
                kafkaHeaders.add(
                    exceptionInfo.exceptionCauseFqcn,
                    cause.javaClass.name.toByteArray(StandardCharsets.UTF_8),
                )
            }
        }
    }
}
