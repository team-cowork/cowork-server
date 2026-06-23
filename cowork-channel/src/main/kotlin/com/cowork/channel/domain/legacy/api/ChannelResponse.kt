package com.cowork.channel.domain.legacy.api

import com.cowork.channel.domain.legacy.internal.ChannelEntity

data class ChannelResponse(val id: Long, val name: String, val type: String, val notice: String?) {
    companion object {
        fun from(entity: ChannelEntity): ChannelResponse = ChannelResponse(
            id = entity.id,
            name = entity.name,
            type = entity.type.name.lowercase(),
            notice = entity.notice,
        )
    }
}
