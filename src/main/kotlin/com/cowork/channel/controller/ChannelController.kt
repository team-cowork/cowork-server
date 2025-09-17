package com.cowork.channel.controller

import com.cowork.channel.dto.AddChannelMemberRequest
import com.cowork.channel.dto.ChannelMemberResponse
import com.cowork.channel.dto.ChannelResponse
import com.cowork.channel.dto.CreateChannelRequest
import com.cowork.channel.dto.UpdateChannelRequest
import com.cowork.channel.service.ChannelMemberService
import com.cowork.channel.service.ChannelService
import com.cowork.global.security.AuthUser
import com.cowork.global.security.CurrentUser
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@Tag(name = "채널")
@RestController
@RequestMapping("/channels")
class ChannelController(
    private val channelService: ChannelService,
    private val channelMemberService: ChannelMemberService,
) {

    @Operation(summary = "채널 생성")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @CurrentUser user: AuthUser,
        @Valid @RequestBody request: CreateChannelRequest,
    ): ChannelResponse = channelService.create(user.id, request)

    @Operation(summary = "채널 조회")
    @GetMapping("/{channelId}")
    fun getChannel(@CurrentUser user: AuthUser, @PathVariable channelId: Long): ChannelResponse =
        channelService.getChannel(channelId, user.id)

    @Operation(summary = "채널 수정")
    @PatchMapping("/{channelId}")
    fun update(
        @CurrentUser user: AuthUser,
        @PathVariable channelId: Long,
        @Valid @RequestBody request: UpdateChannelRequest,
    ): ChannelResponse = channelService.update(channelId, user.id, request)

    @Operation(summary = "채널 삭제")
    @DeleteMapping("/{channelId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@CurrentUser user: AuthUser, @PathVariable channelId: Long) = channelService.delete(channelId, user.id)

    @Operation(summary = "비공개 채널 멤버 추가")
    @PostMapping("/{channelId}/members")
    @ResponseStatus(HttpStatus.CREATED)
    fun addMember(
        @CurrentUser user: AuthUser,
        @PathVariable channelId: Long,
        @Valid @RequestBody request: AddChannelMemberRequest,
    ): ChannelMemberResponse = channelMemberService.addMember(channelId, user.id, request.userId)
}
