package com.cowork.team.domain.team.presentation.controller

import com.cowork.team.domain.team.presentation.data.request.TransferTeamOwnershipRequest
import com.cowork.team.domain.team.presentation.data.response.TeamResponse
import com.cowork.team.domain.team.service.TransferTeamOwnershipService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

@RestController
@Tag(name = "팀")
class TeamOwnershipController(private val transfer: TransferTeamOwnershipService) {
    @PutMapping("/teams/{teamId}/ownership")
    @Operation(
        summary = "팀 소유권 이양",
        description = "현재 소유자의 transfer_team_ownership 권한 필요",
        security = [SecurityRequirement(name = "BearerAuth")],
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "소유권 이양 완료"),
        ApiResponse(responseCode = "400", description = "본인에게 이양하거나 유효하지 않은 요청"),
        ApiResponse(responseCode = "403", description = "소유권 이양 권한 없음"),
        ApiResponse(responseCode = "404", description = "팀 또는 대상 멤버 없음"),
        ApiResponse(responseCode = "503", description = "권한 정보 동기화 중"),
    )
    fun transferOwnership(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable teamId: Long,
        @Valid @RequestBody request: TransferTeamOwnershipRequest,
    ): TeamResponse = transfer.execute(userId, teamId, request.targetUserId)
}
