package com.cowork.team.domain.teamRole.presentation.controller

import com.cowork.team.domain.teamRole.presentation.data.request.CreateTeamRoleRequest
import com.cowork.team.domain.teamRole.presentation.data.request.UpdateTeamRoleRequest
import com.cowork.team.domain.teamRole.presentation.data.response.TeamRoleOperationResponse
import com.cowork.team.domain.teamRole.presentation.data.response.TeamRoleResponse
import com.cowork.team.domain.teamRole.service.AssignTeamRoleService
import com.cowork.team.domain.teamRole.service.CreateTeamRoleService
import com.cowork.team.domain.teamRole.service.DeleteTeamRoleService
import com.cowork.team.domain.teamRole.service.QueryMemberRolesService
import com.cowork.team.domain.teamRole.service.QueryTeamRoleOperationService
import com.cowork.team.domain.teamRole.service.QueryTeamRolesService
import com.cowork.team.domain.teamRole.service.RevokeTeamRoleService
import com.cowork.team.domain.teamRole.service.UpdateTeamRoleService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@Tag(name = "팀 역할", description = "팀 커스텀 역할/권한/색상/우선순위/멘션 설정 API")
@RestController
@RequestMapping("/teams/{teamId}")
class TeamRoleController(
    private val queryTeamRolesService: QueryTeamRolesService,
    private val queryMemberRolesService: QueryMemberRolesService,
    private val queryTeamRoleOperationService: QueryTeamRoleOperationService,
    private val createTeamRoleService: CreateTeamRoleService,
    private val updateTeamRoleService: UpdateTeamRoleService,
    private val deleteTeamRoleService: DeleteTeamRoleService,
    private val assignTeamRoleService: AssignTeamRoleService,
    private val revokeTeamRoleService: RevokeTeamRoleService,
) {

    @Operation(summary = "역할 목록 조회", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(responseCode = "403", description = "권한 없음"),
    )
    @GetMapping("/roles")
    fun getRoles(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable teamId: Long,
    ): List<TeamRoleResponse> = queryTeamRolesService.execute(userId, teamId)

    @Operation(summary = "멤버 역할 목록 조회", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(responseCode = "403", description = "권한 없음"),
        ApiResponse(responseCode = "404", description = "대상 멤버 없음"),
    )
    @GetMapping("/members/{targetUserId}/roles")
    fun getMemberRoles(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable teamId: Long,
        @PathVariable targetUserId: Long,
    ): List<TeamRoleResponse> = queryMemberRolesService.execute(userId, teamId, targetUserId)

    @Operation(summary = "역할 생성", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "202", description = "비동기 생성 요청 접수"),
        ApiResponse(responseCode = "403", description = "권한 없음"),
        ApiResponse(responseCode = "409", description = "Idempotency-Key 재사용 충돌"),
    )
    @PostMapping("/roles")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun createRole(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @PathVariable teamId: Long,
        @RequestBody request: CreateTeamRoleRequest,
    ): TeamRoleOperationResponse = createTeamRoleService.execute(userId, teamId, idempotencyKey, request)

    @Operation(summary = "역할 수정", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "202", description = "비동기 수정 요청 접수"),
        ApiResponse(responseCode = "403", description = "권한 없음"),
        ApiResponse(responseCode = "404", description = "역할 없음"),
        ApiResponse(responseCode = "409", description = "Idempotency-Key 재사용 충돌"),
    )
    @PatchMapping("/roles/{roleId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun updateRole(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @PathVariable teamId: Long,
        @PathVariable roleId: Long,
        @RequestBody request: UpdateTeamRoleRequest,
    ): TeamRoleOperationResponse = updateTeamRoleService.execute(userId, teamId, roleId, idempotencyKey, request)

    @Operation(summary = "역할 삭제", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "202", description = "비동기 삭제 요청 접수"),
        ApiResponse(responseCode = "403", description = "권한 없음"),
        ApiResponse(responseCode = "404", description = "역할 없음"),
        ApiResponse(responseCode = "409", description = "Idempotency-Key 재사용 충돌"),
    )
    @DeleteMapping("/roles/{roleId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun deleteRole(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @PathVariable teamId: Long,
        @PathVariable roleId: Long,
    ): TeamRoleOperationResponse = deleteTeamRoleService.execute(userId, teamId, roleId, idempotencyKey)

    @Operation(summary = "멤버에게 역할 부여", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "202", description = "비동기 부여 요청 접수"),
        ApiResponse(responseCode = "403", description = "권한 없음"),
        ApiResponse(responseCode = "404", description = "대상 멤버 또는 역할 없음"),
        ApiResponse(responseCode = "409", description = "Idempotency-Key 재사용 충돌"),
    )
    @PutMapping("/members/{targetUserId}/roles/{roleId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun assignRole(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @PathVariable teamId: Long,
        @PathVariable targetUserId: Long,
        @PathVariable roleId: Long,
    ): TeamRoleOperationResponse = assignTeamRoleService.execute(
        userId,
        teamId,
        targetUserId,
        roleId,
        idempotencyKey,
    )

    @Operation(summary = "멤버 역할 회수", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "202", description = "비동기 회수 요청 접수"),
        ApiResponse(responseCode = "403", description = "권한 없음"),
        ApiResponse(responseCode = "404", description = "대상 멤버 또는 역할 없음"),
        ApiResponse(responseCode = "409", description = "Idempotency-Key 재사용 충돌"),
    )
    @DeleteMapping("/members/{targetUserId}/roles/{roleId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun revokeRole(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @PathVariable teamId: Long,
        @PathVariable targetUserId: Long,
        @PathVariable roleId: Long,
    ): TeamRoleOperationResponse = revokeTeamRoleService.execute(
        userId,
        teamId,
        targetUserId,
        roleId,
        idempotencyKey,
    )

    @Operation(
        summary = "팀 역할 비동기 작업 상태 조회",
        description = "소유 서비스 검증 실패는 FAILED 상태와 errorCode/errorMessage로 반환됩니다.",
        security = [SecurityRequirement(name = "BearerAuth")],
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "작업 상태 조회 성공"),
        ApiResponse(responseCode = "404", description = "작업 없음"),
    )
    @GetMapping("/role-operations/{operationId}")
    fun getOperation(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable teamId: Long,
        @PathVariable operationId: String,
    ): TeamRoleOperationResponse = queryTeamRoleOperationService.execute(userId, teamId, operationId)
}
