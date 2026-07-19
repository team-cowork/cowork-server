package com.cowork.channel.domain.channel.event

import com.cowork.channel.domain.channel.entity.Channel
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

private const val TOPIC = "channel.event"

@Component
class ChannelEventPublisher(private val kafkaTemplate: KafkaTemplate<String, Any>) {
    private val log = LoggerFactory.getLogger(ChannelEventPublisher::class.java)

    fun publishCreated(channel: Channel) = publish("CREATED", channel)
    fun publishUpdated(channel: Channel) = publish("UPDATED", channel)
    fun publishDeleted(channel: Channel) = publish("DELETED", channel)

    private fun publish(eventType: String, channel: Channel) {
        val event = ChannelEvent(
            eventType = eventType,
            channelId = channel.id,
            teamId = channel.teamId,
            name = channel.name,
            type = channel.type.name,
            viewType = channel.viewType.name,
            description = channel.description,
            isPrivate = channel.isPrivate,
        )
        kafkaTemplate.send(TOPIC, channel.teamId?.toString() ?: "dm-${channel.id}", event)
            .whenComplete { result, ex ->
                if (ex != null) {
                    log.error(
                        "채널 이벤트 발행 실패 [eventType={}, channelId={}]",
                        eventType,
                        channel.id,
                        ex,
                    )
                } else {
                    log.info(
                        "채널 이벤트 발행 성공 [eventType={}, channelId={}, offset={}]",
                        eventType,
                        channel.id,
                        result.recordMetadata.offset(),
                    )
                }
            }
    }
}
