package com.cowork.roadmap.domain.roadmap.presentation.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cowork.roadmap.domain.roadmap.presentation.data.request.CreateRoadmapReqDto;
import com.cowork.roadmap.domain.roadmap.presentation.data.request.QueryRoadmapReqDto;
import com.cowork.roadmap.domain.roadmap.presentation.data.request.UpdateRoadmapReqDto;
import com.cowork.roadmap.domain.roadmap.presentation.data.response.RoadmapResDto;
import com.cowork.roadmap.domain.roadmap.presentation.data.response.RoadmapTreeResDto;
import com.cowork.roadmap.domain.roadmap.service.CreateRoadmapService;
import com.cowork.roadmap.domain.roadmap.service.DeleteRoadmapService;
import com.cowork.roadmap.domain.roadmap.service.ListRoadmapsService;
import com.cowork.roadmap.domain.roadmap.service.ModifyRoadmapService;
import com.cowork.roadmap.domain.roadmap.service.QueryRoadmapService;
import com.cowork.roadmap.domain.roadmap.service.QueryRoadmapTreeService;

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

@Tag(name = "로드맵", description = "전공과 포지션별 로드맵 관리")
@ApiResponses({
        @ApiResponse(responseCode = "401", description = "액세스 토큰이 없거나 유효하지 않음", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CommonApiResponse.class), examples = @ExampleObject(value = "{\"status\":\"401 UNAUTHORIZED\",\"code\":401,\"message\":\"Unauthorized\"}"))),
        @ApiResponse(responseCode = "default", description = "기타 오류", content = @Content(schema = @Schema(implementation = CommonApiResponse.class)))})
@RestController
@RequestMapping("/roadmaps")
@RequiredArgsConstructor
public class RoadmapController {

    private final CreateRoadmapService createRoadmapService;
    private final ListRoadmapsService listRoadmapsService;
    private final QueryRoadmapService queryRoadmapService;
    private final QueryRoadmapTreeService queryRoadmapTreeService;
    private final ModifyRoadmapService modifyRoadmapService;
    private final DeleteRoadmapService deleteRoadmapService;

    @Operation(summary = "로드맵 생성")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "생성 성공", content = @Content(mediaType = "application/json", schema = @Schema(type = "object", requiredProperties = {
                    "status", "code", "message", "data"}), schemaProperties = {
                            @SchemaProperty(name = "status", schema = @Schema(implementation = HttpStatus.class, example = "201 CREATED")),
                            @SchemaProperty(name = "code", schema = @Schema(type = "integer", example = "201")),
                            @SchemaProperty(name = "message", schema = @Schema(type = "string", example = "Created")),
                            @SchemaProperty(name = "data", schema = @Schema(implementation = RoadmapResDto.class))})),
            @ApiResponse(responseCode = "400", description = "요청 값이 잘못되었거나 소유 팀 또는 프로젝트 ID가 없음", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CommonApiResponse.class), examples = @ExampleObject(value = "{\"status\":\"400 BAD_REQUEST\",\"code\":400,\"message\":\"요청 값이 올바르지 않습니다.\"}"))),
            @ApiResponse(responseCode = "403", description = "요청한 작업을 수행할 권한이 없음", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CommonApiResponse.class), examples = @ExampleObject(value = "{\"status\":\"403 FORBIDDEN\",\"code\":403,\"message\":\"요청을 수행할 권한이 없습니다.\"}"))),
            @ApiResponse(responseCode = "503", description = "팀 멤버 정보 동기화 중", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CommonApiResponse.class), examples = @ExampleObject(value = "{\"status\":\"503 SERVICE_UNAVAILABLE\",\"code\":503,\"message\":\"요청을 처리할 준비가 되지 않았습니다.\"}")))})
    @PostMapping
    public ResponseEntity<RoadmapResDto> createRoadmap(
            @Parameter(hidden = true) @RequestHeader("X-User-Id") Long userId,
            @Parameter(hidden = true) @RequestHeader("X-User-Role") String userRole,
            @Valid @RequestBody CreateRoadmapReqDto request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(createRoadmapService.execute(userId, userRole, request));
    }

    @Operation(summary = "로드맵 목록 조회", description = "팀 로드맵은 teamId로, 프로젝트 로드맵은 projectId로 조회합니다. 두 값을 동시에 지정할 수 없습니다. 둘 다 생략하면 scope를 사용하며 기본값은 GLOBAL입니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공", content = @Content(mediaType = "application/json", schema = @Schema(type = "object", requiredProperties = {
                    "status", "code", "message", "data"}), schemaProperties = {
                            @SchemaProperty(name = "status", schema = @Schema(implementation = HttpStatus.class, example = "200 OK")),
                            @SchemaProperty(name = "code", schema = @Schema(type = "integer", example = "200")),
                            @SchemaProperty(name = "message", schema = @Schema(type = "string", example = "OK")),
                            @SchemaProperty(name = "data", array = @ArraySchema(schema = @Schema(implementation = RoadmapResDto.class)))})),
            @ApiResponse(responseCode = "400", description = "teamId와 projectId를 함께 지정했거나 조회 범위에 필요한 ID가 없음", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CommonApiResponse.class), examples = @ExampleObject(value = "{\"status\":\"400 BAD_REQUEST\",\"code\":400,\"message\":\"요청 값이 올바르지 않습니다.\"}"))),
            @ApiResponse(responseCode = "403", description = "요청한 작업을 수행할 권한이 없음", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CommonApiResponse.class), examples = @ExampleObject(value = "{\"status\":\"403 FORBIDDEN\",\"code\":403,\"message\":\"요청을 수행할 권한이 없습니다.\"}"))),
            @ApiResponse(responseCode = "503", description = "팀 멤버 정보 동기화 중", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CommonApiResponse.class), examples = @ExampleObject(value = "{\"status\":\"503 SERVICE_UNAVAILABLE\",\"code\":503,\"message\":\"요청을 처리할 준비가 되지 않았습니다.\"}")))})
    @GetMapping
    public List<RoadmapResDto> listRoadmaps(@Parameter(hidden = true) @RequestHeader("X-User-Id") Long userId,
            @Parameter(hidden = true) @RequestHeader("X-User-Role") String userRole,
            @Valid @ModelAttribute QueryRoadmapReqDto request) {
        return listRoadmapsService
                .execute(userId, userRole, request.scope(), request.category(), request.teamId(), request.projectId());
    }

    @Operation(summary = "로드맵 기본 정보 조회")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공", content = @Content(mediaType = "application/json", schema = @Schema(type = "object", requiredProperties = {
                    "status", "code", "message", "data"}), schemaProperties = {
                            @SchemaProperty(name = "status", schema = @Schema(implementation = HttpStatus.class, example = "200 OK")),
                            @SchemaProperty(name = "code", schema = @Schema(type = "integer", example = "200")),
                            @SchemaProperty(name = "message", schema = @Schema(type = "string", example = "OK")),
                            @SchemaProperty(name = "data", schema = @Schema(implementation = RoadmapResDto.class))})),
            @ApiResponse(responseCode = "403", description = "요청한 작업을 수행할 권한이 없음", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CommonApiResponse.class), examples = @ExampleObject(value = "{\"status\":\"403 FORBIDDEN\",\"code\":403,\"message\":\"요청을 수행할 권한이 없습니다.\"}"))),
            @ApiResponse(responseCode = "404", description = "로드맵 없음", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CommonApiResponse.class), examples = @ExampleObject(value = "{\"status\":\"404 NOT_FOUND\",\"code\":404,\"message\":\"요청한 대상을 찾을 수 없습니다.\"}"))),
            @ApiResponse(responseCode = "503", description = "팀 멤버 정보 동기화 중", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CommonApiResponse.class), examples = @ExampleObject(value = "{\"status\":\"503 SERVICE_UNAVAILABLE\",\"code\":503,\"message\":\"요청을 처리할 준비가 되지 않았습니다.\"}")))})
    @GetMapping("/{roadmapId}")
    public RoadmapResDto getRoadmap(@Parameter(hidden = true) @RequestHeader("X-User-Id") Long userId,
            @Parameter(hidden = true) @RequestHeader("X-User-Role") String userRole,
            @PathVariable Long roadmapId) {
        return queryRoadmapService.execute(userId, userRole, roadmapId);
    }

    @Operation(summary = "로드맵 트리 조회", description = "본문을 제외한 노드 정보와 관련 자료를 계층 구조로 반환합니다. 노드 본문은 GET /roadmaps/nodes/{nodeId}로 조회합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공", content = @Content(mediaType = "application/json", schema = @Schema(type = "object", requiredProperties = {
                    "status", "code", "message", "data"}), schemaProperties = {
                            @SchemaProperty(name = "status", schema = @Schema(implementation = HttpStatus.class, example = "200 OK")),
                            @SchemaProperty(name = "code", schema = @Schema(type = "integer", example = "200")),
                            @SchemaProperty(name = "message", schema = @Schema(type = "string", example = "OK")),
                            @SchemaProperty(name = "data", schema = @Schema(implementation = RoadmapTreeResDto.class))})),
            @ApiResponse(responseCode = "403", description = "요청한 작업을 수행할 권한이 없음", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CommonApiResponse.class), examples = @ExampleObject(value = "{\"status\":\"403 FORBIDDEN\",\"code\":403,\"message\":\"요청을 수행할 권한이 없습니다.\"}"))),
            @ApiResponse(responseCode = "404", description = "로드맵 없음", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CommonApiResponse.class), examples = @ExampleObject(value = "{\"status\":\"404 NOT_FOUND\",\"code\":404,\"message\":\"요청한 대상을 찾을 수 없습니다.\"}"))),
            @ApiResponse(responseCode = "503", description = "팀 멤버 정보 동기화 중", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CommonApiResponse.class), examples = @ExampleObject(value = "{\"status\":\"503 SERVICE_UNAVAILABLE\",\"code\":503,\"message\":\"요청을 처리할 준비가 되지 않았습니다.\"}")))})
    @GetMapping("/{roadmapId}/tree")
    public RoadmapTreeResDto getRoadmapTree(@Parameter(hidden = true) @RequestHeader("X-User-Id") Long userId,
            @Parameter(hidden = true) @RequestHeader("X-User-Role") String userRole,
            @PathVariable Long roadmapId) {
        return queryRoadmapTreeService.execute(userId, userRole, roadmapId);
    }

    @Operation(summary = "로드맵 수정", description = "필드를 생략하거나 null을 보내면 기존 값을 유지합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "수정 성공", content = @Content(mediaType = "application/json", schema = @Schema(type = "object", requiredProperties = {
                    "status", "code", "message", "data"}), schemaProperties = {
                            @SchemaProperty(name = "status", schema = @Schema(implementation = HttpStatus.class, example = "200 OK")),
                            @SchemaProperty(name = "code", schema = @Schema(type = "integer", example = "200")),
                            @SchemaProperty(name = "message", schema = @Schema(type = "string", example = "OK")),
                            @SchemaProperty(name = "data", schema = @Schema(implementation = RoadmapResDto.class))})),
            @ApiResponse(responseCode = "400", description = "제목 또는 종류가 빈 문자열이거나 공백만 있음, 길이 초과", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CommonApiResponse.class), examples = @ExampleObject(value = "{\"status\":\"400 BAD_REQUEST\",\"code\":400,\"message\":\"요청 값이 올바르지 않습니다.\"}"))),
            @ApiResponse(responseCode = "403", description = "로드맵 수정 권한 없음", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CommonApiResponse.class), examples = @ExampleObject(value = "{\"status\":\"403 FORBIDDEN\",\"code\":403,\"message\":\"요청을 수행할 권한이 없습니다.\"}"))),
            @ApiResponse(responseCode = "404", description = "로드맵 없음", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CommonApiResponse.class), examples = @ExampleObject(value = "{\"status\":\"404 NOT_FOUND\",\"code\":404,\"message\":\"요청한 대상을 찾을 수 없습니다.\"}"))),
            @ApiResponse(responseCode = "503", description = "팀 멤버 정보 동기화 중", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CommonApiResponse.class), examples = @ExampleObject(value = "{\"status\":\"503 SERVICE_UNAVAILABLE\",\"code\":503,\"message\":\"요청을 처리할 준비가 되지 않았습니다.\"}")))})
    @PatchMapping("/{roadmapId}")
    public RoadmapResDto updateRoadmap(@Parameter(hidden = true) @RequestHeader("X-User-Id") Long userId,
            @Parameter(hidden = true) @RequestHeader("X-User-Role") String userRole,
            @PathVariable Long roadmapId,
            @Valid @RequestBody UpdateRoadmapReqDto request) {
        return modifyRoadmapService.execute(userId, userRole, roadmapId, request);
    }

    @Operation(summary = "로드맵 삭제")
    @ApiResponses({@ApiResponse(responseCode = "204", description = "응답 본문 없음", content = @Content),
            @ApiResponse(responseCode = "403", description = "요청한 작업을 수행할 권한이 없음", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CommonApiResponse.class), examples = @ExampleObject(value = "{\"status\":\"403 FORBIDDEN\",\"code\":403,\"message\":\"요청을 수행할 권한이 없습니다.\"}"))),
            @ApiResponse(responseCode = "404", description = "로드맵 없음", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CommonApiResponse.class), examples = @ExampleObject(value = "{\"status\":\"404 NOT_FOUND\",\"code\":404,\"message\":\"요청한 대상을 찾을 수 없습니다.\"}"))),
            @ApiResponse(responseCode = "503", description = "팀 멤버 정보 동기화 중", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CommonApiResponse.class), examples = @ExampleObject(value = "{\"status\":\"503 SERVICE_UNAVAILABLE\",\"code\":503,\"message\":\"요청을 처리할 준비가 되지 않았습니다.\"}")))})
    @DeleteMapping("/{roadmapId}")
    public ResponseEntity<Void> deleteRoadmap(@Parameter(hidden = true) @RequestHeader("X-User-Id") Long userId,
            @Parameter(hidden = true) @RequestHeader("X-User-Role") String userRole,
            @PathVariable Long roadmapId) {
        deleteRoadmapService.execute(userId, userRole, roadmapId);
        return ResponseEntity.noContent().build();
    }
}
