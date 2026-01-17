package com.cowork.channel.account.controller

import com.cowork.channel.account.dto.CreateSharedAccountRequest
import com.cowork.channel.account.dto.CredentialResponse
import com.cowork.channel.account.dto.SharedAccountDetailResponse
import com.cowork.channel.account.dto.SharedAccountResponse
import com.cowork.channel.account.dto.UpdateSharedAccountRequest
import com.cowork.channel.account.service.SharedAccountService
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

@Tag(name = "계정 공유")
@RestController
@RequestMapping("/channels/{channelId}/accounts")
class SharedAccountController(
    private val sharedAccountService: SharedAccountService,
) {

    @Operation(summary = "공유 계정 목록 조회")
    @GetMapping
    fun getAccounts(@CurrentUser user: AuthUser, @PathVariable channelId: Long): List<SharedAccountResponse> =
        sharedAccountService.getAccounts(channelId, user.id)

    @Operation(summary = "공유 계정 상세 조회 (최근 복사 이력 포함)")
    @GetMapping("/{accountId}")
    fun getAccount(
        @CurrentUser user: AuthUser,
        @PathVariable channelId: Long,
        @PathVariable accountId: Long,
    ): SharedAccountDetailResponse = sharedAccountService.getAccount(channelId, accountId, user.id)

    @Operation(summary = "공유 계정 등록")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @CurrentUser user: AuthUser,
        @PathVariable channelId: Long,
        @Valid @RequestBody request: CreateSharedAccountRequest,
    ): SharedAccountResponse = sharedAccountService.create(channelId, user.id, request)

    @Operation(summary = "공유 계정 수정")
    @PatchMapping("/{accountId}")
    fun update(
        @CurrentUser user: AuthUser,
        @PathVariable channelId: Long,
        @PathVariable accountId: Long,
        @Valid @RequestBody request: UpdateSharedAccountRequest,
    ): SharedAccountResponse = sharedAccountService.update(channelId, accountId, user.id, request)

    @Operation(summary = "공유 계정 삭제")
    @DeleteMapping("/{accountId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        @CurrentUser user: AuthUser,
        @PathVariable channelId: Long,
        @PathVariable accountId: Long,
    ) = sharedAccountService.delete(channelId, accountId, user.id)

    @Operation(summary = "자격 증명 복사 (복사 이력 기록)")
    @PostMapping("/{accountId}/credential/copy")
    fun copyCredential(
        @CurrentUser user: AuthUser,
        @PathVariable channelId: Long,
        @PathVariable accountId: Long,
    ): CredentialResponse = sharedAccountService.copyCredential(channelId, accountId, user.id)
}
