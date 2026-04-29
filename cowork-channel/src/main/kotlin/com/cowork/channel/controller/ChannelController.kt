package com.cowork.channel.controller

import com.cowork.channel.dto.*
import com.cowork.channel.service.ChannelService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/channels")
class ChannelController(
    private val channelService: ChannelService,
) {

    @PostMapping
    fun createChannel(
        @RequestHeader("X-User-Id") userId: Long,
        @RequestBody request: CreateChannelRequest,
    ): ResponseEntity<ChannelResponse> =
        ResponseEntity.status(201).body(channelService.createChannel(userId, request))

    @GetMapping("/{channelId}")
    fun getChannel(
        @RequestHeader("X-User-Id") userId: Long,
        @PathVariable channelId: Long,
    ): ResponseEntity<ChannelResponse> =
        ResponseEntity.ok(channelService.getChannel(userId, channelId))

    @PatchMapping("/{channelId}")
    fun updateChannel(
        @RequestHeader("X-User-Id") userId: Long,
        @PathVariable channelId: Long,
        @RequestBody request: UpdateChannelRequest,
    ): ResponseEntity<ChannelResponse> =
        ResponseEntity.ok(channelService.updateChannel(userId, channelId, request))

    @DeleteMapping("/{channelId}")
    fun deleteChannel(
        @RequestHeader("X-User-Id") userId: Long,
        @PathVariable channelId: Long,
    ): ResponseEntity<Void> {
        channelService.deleteChannel(userId, channelId)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/{channelId}/members")
    fun addMember(
        @RequestHeader("X-User-Id") userId: Long,
        @PathVariable channelId: Long,
        @RequestBody request: AddMemberRequest,
    ): ResponseEntity<ChannelMemberResponse> =
        ResponseEntity.status(201).body(channelService.addMember(userId, channelId, request))

    @GetMapping("/{channelId}/members")
    fun getMembers(
        @RequestHeader("X-User-Id") userId: Long,
        @PathVariable channelId: Long,
    ): ResponseEntity<List<ChannelMemberResponse>> =
        ResponseEntity.ok(channelService.getMembers(userId, channelId))

    @DeleteMapping("/{channelId}/members/{memberId}")
    fun removeMember(
        @RequestHeader("X-User-Id") userId: Long,
        @PathVariable channelId: Long,
        @PathVariable memberId: Long,
    ): ResponseEntity<Void> {
        channelService.removeMember(userId, channelId, memberId)
        return ResponseEntity.noContent().build()
    }
}
