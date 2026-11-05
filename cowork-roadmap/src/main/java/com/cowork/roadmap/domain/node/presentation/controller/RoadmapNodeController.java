package com.cowork.roadmap.domain.node.presentation.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cowork.roadmap.domain.node.presentation.data.request.CreateNodeReqDto;
import com.cowork.roadmap.domain.node.presentation.data.request.NodeReferenceReqDto;
import com.cowork.roadmap.domain.node.presentation.data.request.ReorderNodesReqDto;
import com.cowork.roadmap.domain.node.presentation.data.request.UpdateNodeReqDto;
import com.cowork.roadmap.domain.node.presentation.data.response.NodeReferenceResDto;
import com.cowork.roadmap.domain.node.presentation.data.response.NodeResDto;
import com.cowork.roadmap.domain.node.service.CreateNodeReferenceService;
import com.cowork.roadmap.domain.node.service.CreateRoadmapNodeService;
import com.cowork.roadmap.domain.node.service.DeleteNodeReferenceService;
import com.cowork.roadmap.domain.node.service.DeleteRoadmapNodeService;
import com.cowork.roadmap.domain.node.service.ListNodeReferencesService;
import com.cowork.roadmap.domain.node.service.ModifyNodeReferenceService;
import com.cowork.roadmap.domain.node.service.ModifyRoadmapNodeService;
import com.cowork.roadmap.domain.node.service.QueryRoadmapNodeService;
import com.cowork.roadmap.domain.node.service.ReorderRoadmapNodesService;

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

@Tag(name = "로드맵 노드", description = "로드맵 노드(문서)와 관련 자료 구성 API")
@ApiResponses({
        @ApiResponse(responseCode = "401", description = "액세스 토큰이 없거나 유효하지 않음", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CommonApiResponse.class), examples = @ExampleObject(value = "{\"status\":\"401 UNAUTHORIZED\",\"code\":401,\"message\":\"Unauthorized\"}"))),
        @ApiResponse(responseCode = "default", description = "기타 오류", content = @Content(schema = @Schema(implementation = CommonApiResponse.class)))})
@RestController
@RequestMapping("/roadmaps")
@RequiredArgsConstructor
public class RoadmapNodeController {

    private final CreateRoadmapNodeService createRoadmapNodeService;
    private final ReorderRoadmapNodesService reorderRoadmapNodesService;
    private final QueryRoadmapNodeService queryRoadmapNodeService;
    private final ModifyRoadmapNodeService modifyRoadmapNodeService;
    private final DeleteRoadmapNodeService deleteRoadmapNodeService;
    private final ListNodeReferencesService listNodeReferencesService;
    private final CreateNodeReferenceService createNodeReferenceService;
    private final ModifyNodeReferenceService modifyNodeReferenceService;
    private final DeleteNodeReferenceService deleteNodeReferenceService;

    @Operation(summary = "노드 생성")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "생성 완료", content = @Content(mediaType = "application/json", schema = @Schema(type = "object", requiredProperties = {
                    "status", "code", "message", "data"}), schemaProperties = {
                            @SchemaProperty(name = "status", schema = @Schema(implementation = HttpStatus.class, example = "201 CREATED")),
                            @SchemaProperty(name = "code", schema = @Schema(type = "integer", example = "201")),
                            @SchemaProperty(name = "message", schema = @Schema(type = "string", example = "Created")),
                            @SchemaProperty(name = "data", schema = @Schema(implementation = NodeResDto.class))})),
            @ApiResponse(responseCode = "400", description = "잘못된 요청 값 또는 다른 로드맵의 상위 노드", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CommonApiResponse.class), examples = @ExampleObject(value = "{\"status\":\"400 BAD_REQUEST\",\"code\":400,\"message\":\"요청 값이 올바르지 않습니다.\"}"))),
            @ApiResponse(responseCode = "403", description = "요청한 작업을 수행할 권한이 없음", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CommonApiResponse.class), examples = @ExampleObject(value = "{\"status\":\"403 FORBIDDEN\",\"code\":403,\"message\":\"요청을 수행할 권한이 없습니다.\"}"))),
            @ApiResponse(responseCode = "404", description = "노드 또는 로드맵 없음", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CommonApiResponse.class), examples = @ExampleObject(value = "{\"status\":\"404 NOT_FOUND\",\"code\":404,\"message\":\"요청한 대상을 찾을 수 없습니다.\"}"))),
            @ApiResponse(responseCode = "503", description = "팀 멤버 정보 동기화 중", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CommonApiResponse.class), examples = @ExampleObject(value = "{\"status\":\"503 SERVICE_UNAVAILABLE\",\"code\":503,\"message\":\"요청을 처리할 준비가 되지 않았습니다.\"}")))})
    @PostMapping("/{roadmapId}/nodes")
    public ResponseEntity<NodeResDto> createNode(@Parameter(hidden = true) @RequestHeader("X-User-Id") Long userId,
            @Parameter(hidden = true) @RequestHeader("X-User-Role") String userRole,
            @PathVariable Long roadmapId,
            @Valid @RequestBody CreateNodeReqDto request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(createRoadmapNodeService.execute(userId, userRole, roadmapId, request));
    }

    @Operation(summary = "형제 노드 순서 변경")
    @ApiResponses({@ApiResponse(responseCode = "204", description = "응답 본문 없음", content = @Content),
            @ApiResponse(responseCode = "400", description = "orderedNodeIds에 같은 부모의 자식이 빠졌거나 중복됨", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CommonApiResponse.class), examples = @ExampleObject(value = "{\"status\":\"400 BAD_REQUEST\",\"code\":400,\"message\":\"요청 값이 올바르지 않습니다.\"}"))),
            @ApiResponse(responseCode = "403", description = "요청한 작업을 수행할 권한이 없음", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CommonApiResponse.class), examples = @ExampleObject(value = "{\"status\":\"403 FORBIDDEN\",\"code\":403,\"message\":\"요청을 수행할 권한이 없습니다.\"}"))),
            @ApiResponse(responseCode = "404", description = "노드 또는 로드맵 없음", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CommonApiResponse.class), examples = @ExampleObject(value = "{\"status\":\"404 NOT_FOUND\",\"code\":404,\"message\":\"요청한 대상을 찾을 수 없습니다.\"}"))),
            @ApiResponse(responseCode = "503", description = "팀 멤버 정보 동기화 중", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CommonApiResponse.class), examples = @ExampleObject(value = "{\"status\":\"503 SERVICE_UNAVAILABLE\",\"code\":503,\"message\":\"요청을 처리할 준비가 되지 않았습니다.\"}")))})
    @PutMapping("/{roadmapId}/nodes/reorder")
    public ResponseEntity<Void> reorderNodes(@Parameter(hidden = true) @RequestHeader("X-User-Id") Long userId,
            @Parameter(hidden = true) @RequestHeader("X-User-Role") String userRole,
            @PathVariable Long roadmapId,
            @Valid @RequestBody ReorderNodesReqDto request) {
        reorderRoadmapNodesService.execute(userId, userRole, roadmapId, request);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "노드 상세 조회 (관련 자료 포함)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 완료", content = @Content(mediaType = "application/json", schema = @Schema(type = "object", requiredProperties = {
                    "status", "code", "message", "data"}), schemaProperties = {
                            @SchemaProperty(name = "status", schema = @Schema(implementation = HttpStatus.class, example = "200 OK")),
                            @SchemaProperty(name = "code", schema = @Schema(type = "integer", example = "200")),
                            @SchemaProperty(name = "message", schema = @Schema(type = "string", example = "OK")),
                            @SchemaProperty(name = "data", schema = @Schema(implementation = NodeResDto.class))})),
            @ApiResponse(responseCode = "403", description = "요청한 작업을 수행할 권한이 없음", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CommonApiResponse.class), examples = @ExampleObject(value = "{\"status\":\"403 FORBIDDEN\",\"code\":403,\"message\":\"요청을 수행할 권한이 없습니다.\"}"))),
            @ApiResponse(responseCode = "404", description = "노드 또는 로드맵 없음", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CommonApiResponse.class), examples = @ExampleObject(value = "{\"status\":\"404 NOT_FOUND\",\"code\":404,\"message\":\"요청한 대상을 찾을 수 없습니다.\"}"))),
            @ApiResponse(responseCode = "503", description = "팀 멤버 정보 동기화 중", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CommonApiResponse.class), examples = @ExampleObject(value = "{\"status\":\"503 SERVICE_UNAVAILABLE\",\"code\":503,\"message\":\"요청을 처리할 준비가 되지 않았습니다.\"}")))})
    @GetMapping("/nodes/{nodeId}")
    public NodeResDto getNode(@Parameter(hidden = true) @RequestHeader("X-User-Id") Long userId,
            @Parameter(hidden = true) @RequestHeader("X-User-Role") String userRole,
            @PathVariable Long nodeId) {
        return queryRoadmapNodeService.execute(userId, userRole, nodeId);
    }

    @Operation(summary = "노드 수정", description = "필드를 생략하거나 null을 보내면 기존 값을 유지합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "수정 성공", content = @Content(mediaType = "application/json", schema = @Schema(type = "object", requiredProperties = {
                    "status", "code", "message", "data"}), schemaProperties = {
                            @SchemaProperty(name = "status", schema = @Schema(implementation = HttpStatus.class, example = "200 OK")),
                            @SchemaProperty(name = "code", schema = @Schema(type = "integer", example = "200")),
                            @SchemaProperty(name = "message", schema = @Schema(type = "string", example = "OK")),
                            @SchemaProperty(name = "data", schema = @Schema(implementation = NodeResDto.class))})),
            @ApiResponse(responseCode = "400", description = "제목이 빈 문자열이거나 공백만 있음, 길이 초과", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CommonApiResponse.class), examples = @ExampleObject(value = "{\"status\":\"400 BAD_REQUEST\",\"code\":400,\"message\":\"요청 값이 올바르지 않습니다.\"}"))),
            @ApiResponse(responseCode = "403", description = "로드맵 수정 권한 없음", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CommonApiResponse.class), examples = @ExampleObject(value = "{\"status\":\"403 FORBIDDEN\",\"code\":403,\"message\":\"요청을 수행할 권한이 없습니다.\"}"))),
            @ApiResponse(responseCode = "404", description = "노드 또는 로드맵 없음", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CommonApiResponse.class), examples = @ExampleObject(value = "{\"status\":\"404 NOT_FOUND\",\"code\":404,\"message\":\"요청한 대상을 찾을 수 없습니다.\"}"))),
            @ApiResponse(responseCode = "503", description = "팀 멤버 정보 동기화 중", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CommonApiResponse.class), examples = @ExampleObject(value = "{\"status\":\"503 SERVICE_UNAVAILABLE\",\"code\":503,\"message\":\"요청을 처리할 준비가 되지 않았습니다.\"}")))})
    @PatchMapping("/nodes/{nodeId}")
    public NodeResDto updateNode(@Parameter(hidden = true) @RequestHeader("X-User-Id") Long userId,
            @Parameter(hidden = true) @RequestHeader("X-User-Role") String userRole,
            @PathVariable Long nodeId,
            @Valid @RequestBody UpdateNodeReqDto request) {
        return modifyRoadmapNodeService.execute(userId, userRole, nodeId, request);
    }

    @Operation(summary = "노드 삭제", description = "하위 노드도 함께 삭제합니다. 연결된 과제는 먼저 삭제해야 합니다.")
    @ApiResponses({@ApiResponse(responseCode = "204", description = "응답 본문 없음", content = @Content),
            @ApiResponse(responseCode = "403", description = "요청한 작업을 수행할 권한이 없음", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CommonApiResponse.class), examples = @ExampleObject(value = "{\"status\":\"403 FORBIDDEN\",\"code\":403,\"message\":\"요청을 수행할 권한이 없습니다.\"}"))),
            @ApiResponse(responseCode = "404", description = "노드 또는 로드맵 없음", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CommonApiResponse.class), examples = @ExampleObject(value = "{\"status\":\"404 NOT_FOUND\",\"code\":404,\"message\":\"요청한 대상을 찾을 수 없습니다.\"}"))),
            @ApiResponse(responseCode = "409", description = "삭제할 노드나 하위 노드에 과제가 연결되어 있음", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CommonApiResponse.class), examples = @ExampleObject(value = "{\"status\":\"409 CONFLICT\",\"code\":409,\"message\":\"현재 상태에서는 요청을 처리할 수 없습니다.\"}"))),
            @ApiResponse(responseCode = "503", description = "팀 멤버 정보 동기화 중", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CommonApiResponse.class), examples = @ExampleObject(value = "{\"status\":\"503 SERVICE_UNAVAILABLE\",\"code\":503,\"message\":\"요청을 처리할 준비가 되지 않았습니다.\"}")))})
    @DeleteMapping("/nodes/{nodeId}")
    public ResponseEntity<Void> deleteNode(@Parameter(hidden = true) @RequestHeader("X-User-Id") Long userId,
            @Parameter(hidden = true) @RequestHeader("X-User-Role") String userRole,
            @PathVariable Long nodeId) {
        deleteRoadmapNodeService.execute(userId, userRole, nodeId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "노드 관련 자료 목록 조회")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 완료", content = @Content(mediaType = "application/json", schema = @Schema(type = "object", requiredProperties = {
                    "status", "code", "message", "data"}), schemaProperties = {
                            @SchemaProperty(name = "status", schema = @Schema(implementation = HttpStatus.class, example = "200 OK")),
                            @SchemaProperty(name = "code", schema = @Schema(type = "integer", example = "200")),
                            @SchemaProperty(name = "message", schema = @Schema(type = "string", example = "OK")),
                            @SchemaProperty(name = "data", array = @ArraySchema(schema = @Schema(implementation = NodeReferenceResDto.class)))})),
            @ApiResponse(responseCode = "403", description = "요청한 작업을 수행할 권한이 없음", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CommonApiResponse.class), examples = @ExampleObject(value = "{\"status\":\"403 FORBIDDEN\",\"code\":403,\"message\":\"요청을 수행할 권한이 없습니다.\"}"))),
            @ApiResponse(responseCode = "404", description = "노드 또는 로드맵 없음", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CommonApiResponse.class), examples = @ExampleObject(value = "{\"status\":\"404 NOT_FOUND\",\"code\":404,\"message\":\"요청한 대상을 찾을 수 없습니다.\"}"))),
            @ApiResponse(responseCode = "503", description = "팀 멤버 정보 동기화 중", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CommonApiResponse.class), examples = @ExampleObject(value = "{\"status\":\"503 SERVICE_UNAVAILABLE\",\"code\":503,\"message\":\"요청을 처리할 준비가 되지 않았습니다.\"}")))})
    @GetMapping("/nodes/{nodeId}/references")
    public List<NodeReferenceResDto> listReferences(@Parameter(hidden = true) @RequestHeader("X-User-Id") Long userId,
            @Parameter(hidden = true) @RequestHeader("X-User-Role") String userRole,
            @PathVariable Long nodeId) {
        return listNodeReferencesService.execute(userId, userRole, nodeId);
    }

    @Operation(summary = "노드 관련 자료 추가")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "생성 완료", content = @Content(mediaType = "application/json", schema = @Schema(type = "object", requiredProperties = {
                    "status", "code", "message", "data"}), schemaProperties = {
                            @SchemaProperty(name = "status", schema = @Schema(implementation = HttpStatus.class, example = "201 CREATED")),
                            @SchemaProperty(name = "code", schema = @Schema(type = "integer", example = "201")),
                            @SchemaProperty(name = "message", schema = @Schema(type = "string", example = "Created")),
                            @SchemaProperty(name = "data", schema = @Schema(implementation = NodeReferenceResDto.class))})),
            @ApiResponse(responseCode = "400", description = "요청 값이 올바르지 않음", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CommonApiResponse.class), examples = @ExampleObject(value = "{\"status\":\"400 BAD_REQUEST\",\"code\":400,\"message\":\"요청 값이 올바르지 않습니다.\"}"))),
            @ApiResponse(responseCode = "403", description = "요청한 작업을 수행할 권한이 없음", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CommonApiResponse.class), examples = @ExampleObject(value = "{\"status\":\"403 FORBIDDEN\",\"code\":403,\"message\":\"요청을 수행할 권한이 없습니다.\"}"))),
            @ApiResponse(responseCode = "404", description = "노드 또는 로드맵 없음", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CommonApiResponse.class), examples = @ExampleObject(value = "{\"status\":\"404 NOT_FOUND\",\"code\":404,\"message\":\"요청한 대상을 찾을 수 없습니다.\"}"))),
            @ApiResponse(responseCode = "503", description = "팀 멤버 정보 동기화 중", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CommonApiResponse.class), examples = @ExampleObject(value = "{\"status\":\"503 SERVICE_UNAVAILABLE\",\"code\":503,\"message\":\"요청을 처리할 준비가 되지 않았습니다.\"}")))})
    @PostMapping("/nodes/{nodeId}/references")
    public ResponseEntity<NodeReferenceResDto> addReference(
            @Parameter(hidden = true) @RequestHeader("X-User-Id") Long userId,
            @Parameter(hidden = true) @RequestHeader("X-User-Role") String userRole,
            @PathVariable Long nodeId,
            @Valid @RequestBody NodeReferenceReqDto request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(createNodeReferenceService.execute(userId, userRole, nodeId, request));
    }

    @Operation(summary = "관련 자료 수정")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "수정 완료", content = @Content(mediaType = "application/json", schema = @Schema(type = "object", requiredProperties = {
                    "status", "code", "message", "data"}), schemaProperties = {
                            @SchemaProperty(name = "status", schema = @Schema(implementation = HttpStatus.class, example = "200 OK")),
                            @SchemaProperty(name = "code", schema = @Schema(type = "integer", example = "200")),
                            @SchemaProperty(name = "message", schema = @Schema(type = "string", example = "OK")),
                            @SchemaProperty(name = "data", schema = @Schema(implementation = NodeReferenceResDto.class))})),
            @ApiResponse(responseCode = "400", description = "요청 값이 올바르지 않음", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CommonApiResponse.class), examples = @ExampleObject(value = "{\"status\":\"400 BAD_REQUEST\",\"code\":400,\"message\":\"요청 값이 올바르지 않습니다.\"}"))),
            @ApiResponse(responseCode = "403", description = "요청한 작업을 수행할 권한이 없음", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CommonApiResponse.class), examples = @ExampleObject(value = "{\"status\":\"403 FORBIDDEN\",\"code\":403,\"message\":\"요청을 수행할 권한이 없습니다.\"}"))),
            @ApiResponse(responseCode = "404", description = "관련 자료 또는 로드맵 없음", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CommonApiResponse.class), examples = @ExampleObject(value = "{\"status\":\"404 NOT_FOUND\",\"code\":404,\"message\":\"요청한 대상을 찾을 수 없습니다.\"}"))),
            @ApiResponse(responseCode = "503", description = "팀 멤버 정보 동기화 중", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CommonApiResponse.class), examples = @ExampleObject(value = "{\"status\":\"503 SERVICE_UNAVAILABLE\",\"code\":503,\"message\":\"요청을 처리할 준비가 되지 않았습니다.\"}")))})
    @PatchMapping("/references/{referenceId}")
    public NodeReferenceResDto updateReference(@Parameter(hidden = true) @RequestHeader("X-User-Id") Long userId,
            @Parameter(hidden = true) @RequestHeader("X-User-Role") String userRole,
            @PathVariable Long referenceId,
            @Valid @RequestBody NodeReferenceReqDto request) {
        return modifyNodeReferenceService.execute(userId, userRole, referenceId, request);
    }

    @Operation(summary = "관련 자료 삭제")
    @ApiResponses({@ApiResponse(responseCode = "204", description = "응답 본문 없음", content = @Content),
            @ApiResponse(responseCode = "403", description = "요청한 작업을 수행할 권한이 없음", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CommonApiResponse.class), examples = @ExampleObject(value = "{\"status\":\"403 FORBIDDEN\",\"code\":403,\"message\":\"요청을 수행할 권한이 없습니다.\"}"))),
            @ApiResponse(responseCode = "404", description = "관련 자료 또는 로드맵 없음", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CommonApiResponse.class), examples = @ExampleObject(value = "{\"status\":\"404 NOT_FOUND\",\"code\":404,\"message\":\"요청한 대상을 찾을 수 없습니다.\"}"))),
            @ApiResponse(responseCode = "503", description = "팀 멤버 정보 동기화 중", content = @Content(mediaType = "application/json", schema = @Schema(implementation = CommonApiResponse.class), examples = @ExampleObject(value = "{\"status\":\"503 SERVICE_UNAVAILABLE\",\"code\":503,\"message\":\"요청을 처리할 준비가 되지 않았습니다.\"}")))})
    @DeleteMapping("/references/{referenceId}")
    public ResponseEntity<Void> deleteReference(@Parameter(hidden = true) @RequestHeader("X-User-Id") Long userId,
            @Parameter(hidden = true) @RequestHeader("X-User-Role") String userRole,
            @PathVariable Long referenceId) {
        deleteNodeReferenceService.execute(userId, userRole, referenceId);
        return ResponseEntity.noContent().build();
    }
}
