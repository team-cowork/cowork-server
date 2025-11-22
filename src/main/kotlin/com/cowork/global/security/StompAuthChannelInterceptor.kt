package com.cowork.global.security

import com.cowork.channel.service.ChannelAccessChecker
import org.springframework.messaging.Message
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.MessageDeliveryException
import org.springframework.messaging.simp.stomp.StompCommand
import org.springframework.messaging.simp.stomp.StompHeaderAccessor
import org.springframework.messaging.support.ChannelInterceptor
import org.springframework.messaging.support.MessageHeaderAccessor
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.stereotype.Component

@Component
class StompAuthChannelInterceptor(
    private val jwtTokenProvider: JwtTokenProvider,
    private val channelAccessChecker: ChannelAccessChecker,
) : ChannelInterceptor {

    override fun preSend(message: Message<*>, channel: MessageChannel): Message<*> {
        val accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor::class.java) ?: return message
        when (accessor.command) {
            StompCommand.CONNECT -> authenticate(accessor)
            StompCommand.SUBSCRIBE -> authorizeSubscription(accessor)
            StompCommand.SEND -> currentUser(accessor)
            else -> Unit
        }
        return message
    }

    private fun authenticate(accessor: StompHeaderAccessor) {
        val token = accessor.getFirstNativeHeader(AUTHORIZATION_HEADER)
            ?.removePrefix(BEARER_PREFIX)
            ?.trim()
            ?: throw MessageDeliveryException("인증이 필요합니다.")
        val user = jwtTokenProvider.parseAccessToken(token)
            ?: throw MessageDeliveryException("유효하지 않은 토큰입니다.")
        accessor.user = UsernamePasswordAuthenticationToken(
            user,
            null,
            listOf(SimpleGrantedAuthority("ROLE_${user.role.name}")),
        )
    }

    private fun authorizeSubscription(accessor: StompHeaderAccessor) {
        val user = currentUser(accessor)
        val destination = accessor.destination ?: throw MessageDeliveryException("구독 경로가 없습니다.")
        CHANNEL_TOPIC.matchEntire(destination)?.let { match ->
            val channelId = match.groupValues[1].toLong()
            val channel = runCatching { channelAccessChecker.findChannel(channelId) }.getOrNull()
            if (channel == null || !channelAccessChecker.canRead(channel, user.id)) {
                throw MessageDeliveryException("채널을 구독할 권한이 없습니다.")
            }
            return
        }
        if (USER_STATUS_TOPIC.matches(destination) || destination.startsWith(USER_QUEUE_PREFIX)) {
            return
        }
        throw MessageDeliveryException("구독할 수 없는 경로입니다.")
    }

    private fun currentUser(accessor: StompHeaderAccessor): AuthUser =
        (accessor.user as? UsernamePasswordAuthenticationToken)?.principal as? AuthUser
            ?: throw MessageDeliveryException("인증이 필요합니다.")

    companion object {
        private const val AUTHORIZATION_HEADER = "Authorization"
        private const val BEARER_PREFIX = "Bearer "
        private const val USER_QUEUE_PREFIX = "/user/queue/"
        private val CHANNEL_TOPIC = Regex("^/topic/channels/(\\d+)(/[a-z-]+)?$")
        private val USER_STATUS_TOPIC = Regex("^/topic/users/\\d+/status$")
    }
}
