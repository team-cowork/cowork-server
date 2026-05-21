package com.cowork.channel.controller

import com.cowork.channel.dto.CreateSharedAccountRequest
import com.cowork.channel.dto.SharedAccountResponse
import com.cowork.channel.dto.UpdateSharedAccountRequest
import com.cowork.channel.service.SharedAccountService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@Tag(name = "계정 공유", description = "ACCOUNT_SHARE 채널의 공유 계정 관리 API")
@RestController
@RequestMapping("/channels/{channelId}/accounts")
class SharedAccountController(
    private val sharedAccountService: SharedAccountService,
) {

    @Operation(summary = "공유 계정 목록 조회", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(responseCode = "400", description = "ACCOUNT_SHARE 채널이 아님"),
        ApiResponse(responseCode = "403", description = "팀 멤버가 아님"),
        ApiResponse(responseCode = "404", description = "채널 없음"),
    )
    @GetMapping
    fun listAccounts(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable channelId: Long,
    ): ResponseEntity<List<SharedAccountResponse>> =
        ResponseEntity.ok(sharedAccountService.listAccounts(userId, channelId))

    @Operation(summary = "공유 계정 상세 조회", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(responseCode = "403", description = "팀 멤버가 아님"),
        ApiResponse(responseCode = "404", description = "채널 또는 계정 없음"),
    )
    @GetMapping("/{accountId}")
    fun getAccount(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable channelId: Long,
        @PathVariable accountId: Long,
    ): ResponseEntity<SharedAccountResponse> =
        ResponseEntity.ok(sharedAccountService.getAccount(userId, channelId, accountId))

    @Operation(summary = "공유 계정 등록 (수동)", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "등록 성공"),
        ApiResponse(responseCode = "400", description = "ACCOUNT_SHARE 채널이 아님 또는 CUSTOM 서비스에 이름 누락"),
        ApiResponse(responseCode = "403", description = "팀 멤버가 아님"),
    )
    @PostMapping
    fun createAccount(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable channelId: Long,
        @RequestBody request: CreateSharedAccountRequest,
    ): ResponseEntity<SharedAccountResponse> =
        ResponseEntity.status(201).body(sharedAccountService.createAccount(userId, channelId, request))

    @Operation(summary = "공유 계정 수정", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "수정 성공"),
        ApiResponse(responseCode = "403", description = "수정 권한 없음 (등록자·채널 생성자·팀 Admin 전용)"),
        ApiResponse(responseCode = "404", description = "채널 또는 계정 없음"),
    )
    @PatchMapping("/{accountId}")
    fun updateAccount(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable channelId: Long,
        @PathVariable accountId: Long,
        @RequestBody request: UpdateSharedAccountRequest,
    ): ResponseEntity<SharedAccountResponse> =
        ResponseEntity.ok(sharedAccountService.updateAccount(userId, channelId, accountId, request))

    @Operation(summary = "공유 계정 삭제", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "삭제 성공"),
        ApiResponse(responseCode = "403", description = "삭제 권한 없음 (등록자·채널 생성자·팀 Admin 전용)"),
        ApiResponse(responseCode = "404", description = "채널 또는 계정 없음"),
    )
    @DeleteMapping("/{accountId}")
    fun deleteAccount(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable channelId: Long,
        @PathVariable accountId: Long,
    ): ResponseEntity<Void> {
        sharedAccountService.deleteAccount(userId, channelId, accountId)
        return ResponseEntity.noContent().build()
    }

    @Operation(
        summary = "credential 복사 (복호화 반환 + 접근 로그 기록)",
        security = [SecurityRequirement(name = "BearerAuth")],
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "복호화된 credential 반환"),
        ApiResponse(responseCode = "403", description = "팀 멤버가 아님"),
        ApiResponse(responseCode = "404", description = "채널, 계정 또는 credential 없음"),
    )
    @PostMapping("/{accountId}/credential/copy")
    fun copyCredential(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable channelId: Long,
        @PathVariable accountId: Long,
    ): ResponseEntity<Map<String, String>> {
        val credential = sharedAccountService.copyCredential(userId, channelId, accountId)
        return ResponseEntity.ok(mapOf("credential" to credential))
    }
}
