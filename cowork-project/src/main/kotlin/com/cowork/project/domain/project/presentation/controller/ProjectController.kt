package com.cowork.project.domain.project.presentation.controller

import com.cowork.project.domain.project.presentation.data.request.CreateProjectReqDto
import com.cowork.project.domain.project.presentation.data.request.UpdateProjectReqDto
import com.cowork.project.domain.project.presentation.data.response.ProjectDetailResDto
import com.cowork.project.domain.project.presentation.data.response.ProjectResDto
import com.cowork.project.domain.project.service.AddProjectMemberService
import com.cowork.project.domain.project.service.CreateProjectService
import com.cowork.project.domain.project.service.DeleteProjectService
import com.cowork.project.domain.project.service.QueryMyProjectsService
import com.cowork.project.domain.project.service.QueryProjectMembersService
import com.cowork.project.domain.project.service.QueryProjectService
import com.cowork.project.domain.project.service.QueryProjectsByTeamIdService
import com.cowork.project.domain.project.service.RemoveProjectMemberService
import com.cowork.project.domain.project.service.UpdateProjectMemberRoleService
import com.cowork.project.domain.project.service.UpdateProjectService
import com.cowork.project.domain.projectMember.presentation.data.request.AddProjectMemberReqDto
import com.cowork.project.domain.projectMember.presentation.data.request.UpdateProjectMemberRoleReqDto
import com.cowork.project.domain.projectMember.presentation.data.response.ProjectMemberResDto
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@Tag(name = "프로젝트", description = "프로젝트 생성/수정/삭제 및 멤버 관리 API")
@RestController
@RequestMapping("/projects")
class ProjectController(
    private val createProjectService: CreateProjectService,
    private val queryProjectService: QueryProjectService,
    private val updateProjectService: UpdateProjectService,
    private val deleteProjectService: DeleteProjectService,
    private val queryProjectsByTeamIdService: QueryProjectsByTeamIdService,
    private val queryMyProjectsService: QueryMyProjectsService,
    private val addProjectMemberService: AddProjectMemberService,
    private val queryProjectMembersService: QueryProjectMembersService,
    private val updateProjectMemberRoleService: UpdateProjectMemberRoleService,
    private val removeProjectMemberService: RemoveProjectMemberService,
) {

    @Operation(summary = "프로젝트 생성", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "생성 성공"),
        ApiResponse(responseCode = "403", description = "권한 없음"),
    )
    @PostMapping
    fun createProject(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @RequestBody request: CreateProjectReqDto,
    ): ResponseEntity<ProjectResDto> = ResponseEntity.status(201).body(createProjectService.execute(userId, request))

    @Operation(summary = "프로젝트 상세 조회", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(responseCode = "404", description = "프로젝트 없음"),
    )
    @GetMapping("/{projectId}")
    fun getProject(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
    ): ResponseEntity<ProjectDetailResDto> = ResponseEntity.ok(queryProjectService.execute(userId, projectId))

    @Operation(summary = "프로젝트 수정", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "수정 성공"),
        ApiResponse(responseCode = "403", description = "권한 없음"),
        ApiResponse(responseCode = "404", description = "프로젝트 없음"),
    )
    @PatchMapping("/{projectId}")
    fun updateProject(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
        @RequestBody request: UpdateProjectReqDto,
    ): ResponseEntity<ProjectResDto> = ResponseEntity.ok(updateProjectService.execute(userId, projectId, request))

    @Operation(summary = "프로젝트 삭제", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "삭제 성공"),
        ApiResponse(responseCode = "403", description = "권한 없음"),
        ApiResponse(responseCode = "404", description = "프로젝트 없음"),
    )
    @DeleteMapping("/{projectId}")
    fun deleteProject(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
    ): ResponseEntity<Void> {
        deleteProjectService.execute(userId, projectId)
        return ResponseEntity.noContent().build()
    }

    @Operation(summary = "팀 프로젝트 목록 조회", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
    )
    @GetMapping
    fun getProjectsByTeamId(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @RequestParam teamId: Long,
        @PageableDefault(size = 20, sort = ["position", "id"]) pageable: Pageable,
    ): ResponseEntity<Page<ProjectResDto>> =
        ResponseEntity.ok(queryProjectsByTeamIdService.execute(userId, teamId, pageable))

    @Operation(summary = "내 프로젝트 목록 조회", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
    )
    @GetMapping("/me")
    fun getMyProjects(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PageableDefault(size = 20, sort = ["createdAt"]) pageable: Pageable,
    ): ResponseEntity<Page<ProjectResDto>> = ResponseEntity.ok(queryMyProjectsService.execute(userId, pageable))

    @Operation(summary = "프로젝트 멤버 추가", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "추가 성공"),
        ApiResponse(responseCode = "403", description = "권한 없음"),
        ApiResponse(responseCode = "404", description = "프로젝트 없음"),
    )
    @PostMapping("/{projectId}/members")
    fun addMember(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
        @RequestBody request: AddProjectMemberReqDto,
    ): ResponseEntity<ProjectMemberResDto> =
        ResponseEntity.status(201).body(addProjectMemberService.execute(userId, projectId, request))

    @Operation(summary = "프로젝트 멤버 목록 조회", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(responseCode = "404", description = "프로젝트 없음"),
    )
    @GetMapping("/{projectId}/members")
    fun getMembers(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
    ): ResponseEntity<List<ProjectMemberResDto>> =
        ResponseEntity.ok(queryProjectMembersService.execute(userId, projectId))

    @Operation(summary = "프로젝트 멤버 역할 변경", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "변경 성공"),
        ApiResponse(responseCode = "403", description = "권한 없음"),
        ApiResponse(responseCode = "404", description = "멤버 없음"),
    )
    @PatchMapping("/{projectId}/members/{memberId}")
    fun updateMemberRole(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
        @PathVariable memberId: Long,
        @RequestBody request: UpdateProjectMemberRoleReqDto,
    ): ResponseEntity<ProjectMemberResDto> =
        ResponseEntity.ok(updateProjectMemberRoleService.execute(userId, projectId, memberId, request))

    @Operation(summary = "프로젝트 멤버 제거", security = [SecurityRequirement(name = "BearerAuth")])
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "제거 성공"),
        ApiResponse(responseCode = "403", description = "권한 없음"),
        ApiResponse(responseCode = "404", description = "멤버 없음"),
    )
    @DeleteMapping("/{projectId}/members/{memberId}")
    fun removeMember(
        @Parameter(hidden = true) @RequestHeader("X-User-Id") userId: Long,
        @PathVariable projectId: Long,
        @PathVariable memberId: Long,
    ): ResponseEntity<Void> {
        removeProjectMemberService.execute(userId, projectId, memberId)
        return ResponseEntity.noContent().build()
    }
}
