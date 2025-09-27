package com.cowork.channel.domain

enum class ChannelViewType(
    val channelType: ChannelType,
) {
    TEXT(ChannelType.TEXT),
    VOICE(ChannelType.VOICE),
    WEBHOOK(ChannelType.TEXT),
    ACCOUNT_SHARE(ChannelType.TEXT),
    FILE_SHARE(ChannelType.TEXT),
    MEETING_NOTE(ChannelType.TEXT),
    ;

    val supportsChat: Boolean get() = this == TEXT || this == WEBHOOK || this == FILE_SHARE
}
