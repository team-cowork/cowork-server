package com.cowork.channel.domain.channel.service.impl

import com.cowork.channel.domain.channel.presentation.data.response.ArchivedChannelPageResponse
import com.cowork.channel.domain.channel.presentation.data.response.ChannelResponse
import com.cowork.channel.domain.channel.repository.ChannelRepository
import com.cowork.channel.domain.channel.repository.LikePattern
import com.cowork.channel.domain.channel.service.SearchArchivedChannelsService
import com.cowork.channel.domain.channel.service.TeamPermissionService
import com.cowork.channel.domain.channelRolePolicy.service.ChannelMessageReadPolicyEvaluator
import org.springframework.data.domain.Limit
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException
import java.time.LocalDateTime
import java.time.format.DateTimeParseException
import java.util.Base64

@Service
class SearchArchivedChannelsServiceImpl(
    private val channelRepository: ChannelRepository,
    private val teamPermissionService: TeamPermissionService,
    private val messageReadPolicyEvaluator: ChannelMessageReadPolicyEvaluator,
) : SearchArchivedChannelsService {

    /** 권한 필터 전에 조회한 마지막 행으로 커서를 만들어, 읽을 수 없는 채널이 많아도 다음 페이지로 진행한다. */
    @Transactional(readOnly = true)
    override fun execute(
        userId: Long,
        teamId: Long,
        channelId: Long?,
        projectId: Long?,
        q: String?,
        cursor: String?,
        size: Int,
    ): ArchivedChannelPageResponse {
        if (size !in 1..MAX_SIZE) {
            throw ExpectedException("조회 크기는 1 이상 100 이하여야 합니다.", HttpStatus.BAD_REQUEST)
        }
        teamPermissionService.requireTeamMember(teamId, userId)
        val position = cursor?.let(::decode)
        val archived = channelRepository.searchArchived(
            teamId,
            channelId,
            projectId,
            q?.trim()?.ifEmpty { null }?.let(LikePattern::escape),
            position?.archivedAt,
            position?.id,
            Limit.of(size),
        )
        val nextCursor = archived.takeIf { it.size == size }?.last()
            ?.let { encode(CursorPosition(checkNotNull(it.archivedAt), it.id)) }
        val readable = messageReadPolicyEvaluator.filterReadable(teamId, userId, archived)
        return ArchivedChannelPageResponse(readable.map(ChannelResponse::of), nextCursor)
    }

    private data class CursorPosition(val archivedAt: LocalDateTime, val id: Long)

    private fun encode(position: CursorPosition): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString("${position.archivedAt},${position.id}".toByteArray())

    private fun decode(cursor: String): CursorPosition = try {
        val (archivedAt, id) = String(Base64.getUrlDecoder().decode(cursor)).split(',', limit = 2)
        CursorPosition(LocalDateTime.parse(archivedAt), id.toLong())
    } catch (exception: IllegalArgumentException) {
        throw invalidCursor()
    } catch (exception: DateTimeParseException) {
        throw invalidCursor()
    } catch (exception: IndexOutOfBoundsException) {
        throw invalidCursor()
    }

    private fun invalidCursor() = ExpectedException("커서 형식이 올바르지 않습니다.", HttpStatus.BAD_REQUEST)

    private companion object {
        const val MAX_SIZE = 100
    }
}
