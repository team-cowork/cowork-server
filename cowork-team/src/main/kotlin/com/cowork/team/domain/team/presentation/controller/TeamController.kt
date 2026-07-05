package com.cowork.team.domain.team.presentation.controller

import com.cowork.team.domain.team.presentation.data.request.CreateTeamRequest
import com.cowork.team.domain.team.presentation.data.request.IconConfirmRequest
import com.cowork.team.domain.team.presentation.data.request.IconPresignedUrlRequest
import com.cowork.team.domain.team.presentation.data.request.UpdateIconRequest
import com.cowork.team.domain.team.presentation.data.request.UpdateTeamRequest
import com.cowork.team.domain.team.presentation.data.response.IconConfirmResponse
import com.cowork.team.domain.team.presentation.data.response.IconPresignedUrlResponse
import com.cowork.team.domain.team.presentation.data.response.TeamResponse
import com.cowork.team.domain.team.presentation.data.response.TeamSummaryResponse
import com.cowork.team.domain.team.service.ConfirmIconUploadService
import com.cowork.team.domain.team.service.CreateTeamService
import com.cowork.team.domain.team.service.DeleteTeamIconService
import com.cowork.team.domain.team.service.DeleteTeamService
import com.cowork.team.domain.team.service.GenerateIconPresignedUrlService
import com.cowork.team.domain.team.service.GetMyTeamsService
import com.cowork.team.domain.team.service.GetTeamService
import com.cowork.team.domain.team.service.UpdateTeamIconService
import com.cowork.team.domain.team.service.UpdateTeamService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

@Tag(name = "팀", description = "팀 생성/수정/삭제 API")
@RestController
@RequestMapping("/teams")
class TeamController(
    private val generateIconPresignedUrlService: GenerateIconPresignedUrlService,
    private val confirmIconUploadService: ConfirmIconUploadService,
    private val createTeamService: CreateTeamService,
    private val getMyTeamsService: GetMyTeamsService,
    private val getTeamService: GetTeamService,
    private val updateTeamService: UpdateTeamService,
    private val updateTeamIconService: UpdateTeamIconService,
    private val deleteTeamIconService: DeleteTeamIconService,
    private val deleteTeamService: DeleteTeamService,
) {

    @Operation(summary = "팀 아이콘 Presigned URL 발급", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "발급 성공"),
        ApiResponse(responseCode = "400", description = "허용되지 않는 파일 형식"),
    )
    @PostMapping("/icon/presigned")
    fun generateIconPresignedUrl(@RequestBody request: IconPresignedUrlRequest): IconPresignedUrlResponse =
        generateIconPresignedUrlService.generateIconPresignedUrl(request.contentType)

    @Operation(summary = "팀 아이콘 업로드 확인", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "확인 성공"),
        ApiResponse(responseCode = "400", description = "유효하지 않은 objectKey"),
        ApiResponse(responseCode = "409", description = "S3에 파일 없음 (업로드 미완료)"),
        ApiResponse(responseCode = "413", description = "파일 크기 초과"),
    )
    @PostMapping("/icon/confirm")
    fun confirmIconUpload(@RequestBody request: IconConfirmRequest): IconConfirmResponse =
        confirmIconUploadService.confirmIconUpload(request.objectKey)

    @Operation(summary = "팀 생성", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "팀 생성 성공"),
        ApiResponse(responseCode = "400", description = "잘못된 요청"),
    )
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createTeam(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @RequestBody request: CreateTeamRequest,
    ): TeamResponse = createTeamService.createTeam(userId, request)

    @Operation(summary = "내 팀 목록 조회", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @GetMapping
    fun getMyTeams(@Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long): List<TeamSummaryResponse> =
        getMyTeamsService.getMyTeams(userId)

    @Operation(summary = "팀 상세 조회", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(responseCode = "404", description = "팀 없음"),
    )
    @GetMapping("/{teamId}")
    fun getTeam(@PathVariable teamId: Long): TeamResponse = getTeamService.getTeam(teamId)

    @Operation(summary = "팀 정보 수정", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "수정 성공"),
        ApiResponse(responseCode = "403", description = "권한 없음"),
        ApiResponse(responseCode = "404", description = "팀 없음"),
    )
    @PatchMapping("/{teamId}")
    fun updateTeam(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable teamId: Long,
        @RequestBody request: UpdateTeamRequest,
    ): TeamResponse = updateTeamService.updateTeam(userId, teamId, request)

    @Operation(summary = "팀 아이콘 교체", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "교체 성공"),
        ApiResponse(responseCode = "403", description = "권한 없음"),
        ApiResponse(responseCode = "404", description = "팀 없음"),
    )
    @PatchMapping("/{teamId}/icon")
    fun updateIcon(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable teamId: Long,
        @RequestBody request: UpdateIconRequest,
    ): IconConfirmResponse = updateTeamIconService.updateIcon(userId, teamId, request.iconUrl)

    @Operation(summary = "팀 아이콘 제거", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "제거 성공"),
        ApiResponse(responseCode = "403", description = "권한 없음"),
        ApiResponse(responseCode = "404", description = "팀 없음 또는 아이콘 없음"),
    )
    @DeleteMapping("/{teamId}/icon")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteIcon(@Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long, @PathVariable teamId: Long) {
        deleteTeamIconService.deleteIcon(userId, teamId)
    }

    @Operation(summary = "팀 삭제", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "삭제 성공"),
        ApiResponse(responseCode = "403", description = "권한 없음"),
        ApiResponse(responseCode = "404", description = "팀 없음"),
    )
    @DeleteMapping("/{teamId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteTeam(@Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long, @PathVariable teamId: Long) {
        deleteTeamService.deleteTeam(userId, teamId)
    }
}
