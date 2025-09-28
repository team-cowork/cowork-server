package com.cowork.channel

import com.cowork.channel.domain.Channel
import com.cowork.channel.domain.ChannelType
import com.cowork.channel.domain.ChannelViewType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChannelDomainTest {

    private fun channel(viewType: ChannelViewType) = Channel(
        teamId = 1, projectId = null, name = "general", viewType = viewType, description = "설명",
        isPrivate = false, position = 0, createdBy = 1,
    )

    @Test
    fun `음성 채널만 음성 종류로 저장한다`() {
        assertEquals(ChannelType.VOICE, channel(ChannelViewType.VOICE).type)
        assertEquals(ChannelType.TEXT, channel(ChannelViewType.ACCOUNT_SHARE).type)
    }

    @Test
    fun `텍스트, 웹훅, 파일 공유 채널만 메시지를 지원한다`() {
        assertTrue(ChannelViewType.FILE_SHARE.supportsChat)
        assertFalse(ChannelViewType.MEETING_NOTE.supportsChat)
        assertFalse(ChannelViewType.VOICE.supportsChat)
    }
}
