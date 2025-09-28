package com.cowork.channel

import com.cowork.channel.domain.Channel
import com.cowork.channel.domain.ChannelThread
import com.cowork.channel.domain.ChannelType
import com.cowork.channel.domain.ChannelViewType
import com.cowork.channel.domain.Webhook
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
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

    @Test
    fun `빈 설명으로 수정하면 설명을 비운다`() {
        val channel = channel(ChannelViewType.TEXT)

        channel.update(null, " ", true)

        assertNull(channel.description)
        assertTrue(channel.isPrivate)
    }

    @Test
    fun `웹훅 아바타를 빈 값으로 수정하면 기본 아바타로 돌아간다`() {
        val webhook = Webhook(channelId = 1, name = "CI", avatarUrl = "https://img", token = "t", createdBy = 1)

        webhook.update(null, "")

        assertNull(webhook.avatarUrl)
        assertEquals("CI", webhook.name)
    }

    @Test
    fun `스레드 보관 여부만 바꿀 수 있다`() {
        val thread = ChannelThread(channelId = 1, name = "논의", parentMessageId = 5, createdBy = 1)

        thread.update(null, true)

        assertEquals("논의", thread.name)
        assertTrue(thread.isArchived)
    }
}
