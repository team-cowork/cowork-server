package com.cowork.channel.account.controller

import com.cowork.channel.account.dto.CreateSharedAccountRequest
import com.cowork.channel.account.dto.CredentialResponse
import com.cowork.channel.account.dto.SharedAccountDetailResponse
import com.cowork.channel.account.dto.SharedAccountResponse
import com.cowork.channel.account.dto.UpdateSharedAccountRequest
import com.cowork.channel.account.service.SharedAccountService
import com.cowork.global.security.AuthUser
import com.cowork.global.security.CurrentUser
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/channels/{channelId}/accounts")
class SharedAccountController(
    private val sharedAccountService: SharedAccountService,
) {

    @GetMapping
    fun getAccounts(@CurrentUser user: AuthUser, @PathVariable channelId: Long): List<SharedAccountResponse> =
        sharedAccountService.getAccounts(channelId, user.id)

    @GetMapping("/{accountId}")
    fun getAccount(
        @CurrentUser user: AuthUser,
        @PathVariable channelId: Long,
        @PathVariable accountId: Long,
    ): SharedAccountDetailResponse = sharedAccountService.getAccount(channelId, accountId, user.id)

    @PostMapping
    fun create(
        @CurrentUser user: AuthUser,
        @PathVariable channelId: Long,
        @RequestBody request: CreateSharedAccountRequest,
    ): SharedAccountResponse = sharedAccountService.create(channelId, user.id, request)

    @PatchMapping("/{accountId}")
    fun update(
        @CurrentUser user: AuthUser,
        @PathVariable channelId: Long,
        @PathVariable accountId: Long,
        @RequestBody request: UpdateSharedAccountRequest,
    ): SharedAccountResponse = sharedAccountService.update(channelId, accountId, user.id, request)
}
