package com.cowork.channel.domain.sharedAccount.service

import com.cowork.channel.domain.channel.entity.Channel
import com.cowork.channel.domain.channel.entity.ChannelViewType
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import team.themoment.sdk.exception.ExpectedException

@Service
class SharedAccountAccessGuard {

    fun requireAccountShareChannel(channel: Channel) {
        if (channel.viewType != ChannelViewType.ACCOUNT_SHARE) {
            throw ExpectedException("ACCOUNT_SHARE 채널에서만 계정 공유 기능을 사용할 수 있습니다.", HttpStatus.BAD_REQUEST)
        }
    }
}
