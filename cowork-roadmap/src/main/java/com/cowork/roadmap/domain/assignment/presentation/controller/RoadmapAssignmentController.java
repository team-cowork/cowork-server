package com.cowork.roadmap.domain.assignment.presentation.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cowork.roadmap.domain.assignment.presentation.data.request.CreateAssignmentReqDto;
import com.cowork.roadmap.domain.assignment.presentation.data.request.UpdateAssignmentStatusReqDto;
import com.cowork.roadmap.domain.assignment.presentation.data.response.AssignmentResDto;
import com.cowork.roadmap.domain.assignment.service.CreateRoadmapAssignmentService;
import com.cowork.roadmap.domain.assignment.service.DeleteRoadmapAssignmentService;
import com.cowork.roadmap.domain.assignment.service.ListMyRoadmapAssignmentsService;
import com.cowork.roadmap.domain.assignment.service.ListRoadmapAssignmentsService;
import com.cowork.roadmap.domain.assignment.service.ModifyRoadmapAssignmentStatusService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.SchemaProperty;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import team.themoment.sdk.response.CommonApiResponse;

@Tag(name = "로드맵 과제", description = "온보딩 과제와 진행 상태 관리")
@ApiResponses({
        @ApiResponse(responseCode = "401", description = "액세스 토큰이 없거나 유효하지 않음", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CommonApiResponse.class), examples = @ExampleObject(value = "{\"status\":\"401 UNAUTHORIZED\",\"code\":401,\"message\":\"Unauthorized\"}"))),
        @ApiResponse(description = "기타 오류", content = @Content(schema = @Schema(implementation = CommonApiResponse.class)))})
@RestController
@RequestMapping("/roadmaps")
@RequiredArgsConstructor
public class RoadmapAssignmentController {

    private final CreateRoadmapAssignmentService createRoadmapAssignmentService;
    private final ListMyRoadmapAssignmentsService listMyRoadmapAssignmentsService;
    private final ListRoadmapAssignmentsService listRoadmapAssignmentsService;
    private final ModifyRoadmapAssignmentStatusService modifyRoadmapAssignmentStatusService;
    private final DeleteRoadmapAssignmentService deleteRoadmapAssignmentService;

    @Operation(summary = "과제 출제", description = "TEAM 또는 PROJECT 로드맵에 TEAM 범위 과제를 출제합니다. PROJECT 범위 할당은 아직 지원하지 않습니다. 노드는 해당 로드맵에 속해야 하며 담당자는 소유 팀의 활성 멤버여야 합니다. 로드맵 조회 권한과 함께 계정 권한 `create_team_assignments` 또는 소유 팀의 `administrator`, `manage_roadmaps`, `manage_team` 중 하나의 권한이 필요합니다. `manage_team`은 전환 기간에만 허용합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "출제 성공", content = @Content(mediaType = "application/json", schema = @Schema(type = "object", requiredProperties = {
                    "status", "code", "message", "data"}), schemaProperties = {
                            @SchemaProperty(name = "status", schema = @Schema(implementation = HttpStatus.class, example = "201 CREATED")),
                            @SchemaProperty(name = "code", schema = @Schema(type = "integer", example = "201")),
                            @SchemaProperty(name = "message", schema = @Schema(type = "string", example = "Created")),
                            @SchemaProperty(name = "data", schema = @Schema(implementation = AssignmentResDto.class))})),
            @ApiResponse(responseCode = "400", description = "할당 범위나 필수 값이 잘못되었거나 로드맵, 노드, 팀 또는 담당자가 할당 조건을 충족하지 않음", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CommonApiResponse.class), examples = @ExampleObject(value = "{\"status\":\"400 BAD_REQUEST\",\"code\":400,\"message\":\"요청 값이 올바르지 않습니다.\"}"))),
            @ApiResponse(responseCode = "403", description = "로드맵 조회 권한 또는 과제 출제 권한 없음", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CommonApiResponse.class), examples = @ExampleObject(value = "{\"status\":\"403 FORBIDDEN\",\"code\":403,\"message\":\"요청을 수행할 권한이 없습니다.\"}"))),
            @ApiResponse(responseCode = "404", description = "로드맵 또는 노드 없음", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CommonApiResponse.class), examples = @ExampleObject(value = "{\"status\":\"404 NOT_FOUND\",\"code\":404,\"message\":\"요청한 대상을 찾을 수 없습니다.\"}"))),
            @ApiResponse(responseCode = "409", description = "같은 로드맵, 노드, 담당자의 과제가 이미 있음", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CommonApiResponse.class), examples = @ExampleObject(value = "{\"status\":\"409 CONFLICT\",\"code\":409,\"message\":\"현재 상태에서는 요청을 처리할 수 없습니다.\"}"))),
            @ApiResponse(responseCode = "503", description = "권한 판정에 필요한 정보 동기화 중", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CommonApiResponse.class), examples = @ExampleObject(value = "{\"status\":\"503 SERVICE_UNAVAILABLE\",\"code\":503,\"message\":\"권한 정보를 동기화하는 중입니다.\"}")))})
    @PostMapping("/assignments")
    public ResponseEntity<AssignmentResDto> createAssignment(
            @Parameter(hidden = true) @RequestHeader("X-User-Id") Long userId,
            @Valid @RequestBody CreateAssignmentReqDto request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(createRoadmapAssignmentService.execute(userId, request));
    }

    @Operation(summary = "내 과제 목록 조회")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공", content = @Content(mediaType = "application/json", schema = @Schema(type = "object", requiredProperties = {
                    "status", "code", "message", "data"}), schemaProperties = {
                            @SchemaProperty(name = "status", schema = @Schema(implementation = HttpStatus.class, example = "200 OK")),
                            @SchemaProperty(name = "code", schema = @Schema(type = "integer", example = "200")),
                            @SchemaProperty(name = "message", schema = @Schema(type = "string", example = "OK")),
                            @SchemaProperty(name = "data", array = @ArraySchema(schema = @Schema(implementation = AssignmentResDto.class)))}))})
    @GetMapping("/assignments/me")
    public List<AssignmentResDto> listMyAssignments(@Parameter(hidden = true) @RequestHeader("X-User-Id") Long userId) {
        return listMyRoadmapAssignmentsService.execute(userId);
    }

    @Operation(summary = "로드맵별 과제 목록 조회", description = "조회 권한이 있는 로드맵에서 소속 팀의 과제를 반환합니다. 다른 팀의 과제를 열람하려면 `read_all_team_roadmaps`가 필요합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공", content = @Content(mediaType = "application/json", schema = @Schema(type = "object", requiredProperties = {
                    "status", "code", "message", "data"}), schemaProperties = {
                            @SchemaProperty(name = "status", schema = @Schema(implementation = HttpStatus.class, example = "200 OK")),
                            @SchemaProperty(name = "code", schema = @Schema(type = "integer", example = "200")),
                            @SchemaProperty(name = "message", schema = @Schema(type = "string", example = "OK")),
                            @SchemaProperty(name = "data", array = @ArraySchema(schema = @Schema(implementation = AssignmentResDto.class)))})),
            @ApiResponse(responseCode = "403", description = "요청한 작업을 수행할 권한이 없음", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CommonApiResponse.class), examples = @ExampleObject(value = "{\"status\":\"403 FORBIDDEN\",\"code\":403,\"message\":\"요청을 수행할 권한이 없습니다.\"}"))),
            @ApiResponse(responseCode = "404", description = "로드맵 없음", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CommonApiResponse.class), examples = @ExampleObject(value = "{\"status\":\"404 NOT_FOUND\",\"code\":404,\"message\":\"요청한 대상을 찾을 수 없습니다.\"}"))),
            @ApiResponse(responseCode = "503", description = "권한 판정에 필요한 정보 동기화 중", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CommonApiResponse.class), examples = @ExampleObject(value = "{\"status\":\"503 SERVICE_UNAVAILABLE\",\"code\":503,\"message\":\"권한 정보를 동기화하는 중입니다.\"}")))})
    @GetMapping("/{roadmapId}/assignments")
    public List<AssignmentResDto> listByRoadmap(@Parameter(hidden = true) @RequestHeader("X-User-Id") Long userId,
            @PathVariable Long roadmapId) {
        return listRoadmapAssignmentsService.execute(userId, roadmapId);
    }

    @Operation(summary = "과제 진행 상태 변경", description = "과제 팀의 활성 멤버인 출제자나 담당자가 변경할 수 있습니다. 계정 권한 `update_team_assignment_status`를 가진 사용자도 변경할 수 있습니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "수정 성공", content = @Content(mediaType = "application/json", schema = @Schema(type = "object", requiredProperties = {
                    "status", "code", "message", "data"}), schemaProperties = {
                            @SchemaProperty(name = "status", schema = @Schema(implementation = HttpStatus.class, example = "200 OK")),
                            @SchemaProperty(name = "code", schema = @Schema(type = "integer", example = "200")),
                            @SchemaProperty(name = "message", schema = @Schema(type = "string", example = "OK")),
                            @SchemaProperty(name = "data", schema = @Schema(implementation = AssignmentResDto.class))})),
            @ApiResponse(responseCode = "400", description = "요청 값이 올바르지 않음", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CommonApiResponse.class), examples = @ExampleObject(value = "{\"status\":\"400 BAD_REQUEST\",\"code\":400,\"message\":\"요청 값이 올바르지 않습니다.\"}"))),
            @ApiResponse(responseCode = "403", description = "요청한 작업을 수행할 권한이 없음", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CommonApiResponse.class), examples = @ExampleObject(value = "{\"status\":\"403 FORBIDDEN\",\"code\":403,\"message\":\"요청을 수행할 권한이 없습니다.\"}"))),
            @ApiResponse(responseCode = "404", description = "과제 없음", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CommonApiResponse.class), examples = @ExampleObject(value = "{\"status\":\"404 NOT_FOUND\",\"code\":404,\"message\":\"요청한 대상을 찾을 수 없습니다.\"}"))),
            @ApiResponse(responseCode = "503", description = "권한 판정에 필요한 정보 동기화 중", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CommonApiResponse.class), examples = @ExampleObject(value = "{\"status\":\"503 SERVICE_UNAVAILABLE\",\"code\":503,\"message\":\"권한 정보를 동기화하는 중입니다.\"}")))})
    @PatchMapping("/assignments/{assignmentId}/status")
    public AssignmentResDto updateStatus(@Parameter(hidden = true) @RequestHeader("X-User-Id") Long userId,
            @PathVariable Long assignmentId,
            @Valid @RequestBody UpdateAssignmentStatusReqDto request) {
        return modifyRoadmapAssignmentStatusService.execute(userId, assignmentId, request);
    }

    @Operation(summary = "과제 삭제", description = "과제 팀의 활성 멤버 중 출제자 또는 `administrator`, `manage_roadmaps`, `manage_team` 중 하나의 권한을 가진 사용자가 삭제할 수 있습니다. `manage_team`은 전환 기간에만 허용합니다. 계정 권한 `delete_team_assignments`를 가진 사용자도 삭제할 수 있습니다.")
    @ApiResponses({@ApiResponse(responseCode = "204", description = "응답 본문 없음", content = @Content),
            @ApiResponse(responseCode = "403", description = "요청한 작업을 수행할 권한이 없음", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CommonApiResponse.class), examples = @ExampleObject(value = "{\"status\":\"403 FORBIDDEN\",\"code\":403,\"message\":\"요청을 수행할 권한이 없습니다.\"}"))),
            @ApiResponse(responseCode = "404", description = "과제 없음", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CommonApiResponse.class), examples = @ExampleObject(value = "{\"status\":\"404 NOT_FOUND\",\"code\":404,\"message\":\"요청한 대상을 찾을 수 없습니다.\"}"))),
            @ApiResponse(responseCode = "503", description = "권한 판정에 필요한 정보 동기화 중", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CommonApiResponse.class), examples = @ExampleObject(value = "{\"status\":\"503 SERVICE_UNAVAILABLE\",\"code\":503,\"message\":\"권한 정보를 동기화하는 중입니다.\"}")))})
    @DeleteMapping("/assignments/{assignmentId}")
    public ResponseEntity<Void> deleteAssignment(@Parameter(hidden = true) @RequestHeader("X-User-Id") Long userId,
            @PathVariable Long assignmentId) {
        deleteRoadmapAssignmentService.execute(userId, assignmentId);
        return ResponseEntity.noContent().build();
    }
}
