package com.cowork.channel.event

import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

private const val TOPIC = "channel.member.event"

@Component
class ChannelMemberEventPublisher(
    private val kafkaTemplate: KafkaTemplate<String, Any>,
) {
    private val log = LoggerFactory.getLogger(ChannelMemberEventPublisher::class.java)

    fun publishJoin(channelId: Long, teamId: Long, userId: Long, role: String = "MEMBER") =
        publish(ChannelMemberEvent("JOIN", channelId, teamId, userId, role))

    fun publishLeave(channelId: Long, teamId: Long, userId: Long, role: String = "MEMBER") =
        publish(ChannelMemberEvent("LEAVE", channelId, teamId, userId, role))

    private fun publish(event: ChannelMemberEvent) {
        kafkaTemplate.send(TOPIC, event.channelId.toString(), event)
            .whenComplete { result, ex ->
                if (ex != null) {
                    log.error(
                        "채널 멤버 이벤트 발행 실패 [eventType={}, channelId={}, userId={}]",
                        event.eventType, event.channelId, event.userId, ex,
                    )
                } else {
                    log.info(
                        "채널 멤버 이벤트 발행 성공 [eventType={}, channelId={}, userId={}, offset={}]",
                        event.eventType, event.channelId, event.userId, result.recordMetadata.offset(),
                    )
                }
            }
    }
}
