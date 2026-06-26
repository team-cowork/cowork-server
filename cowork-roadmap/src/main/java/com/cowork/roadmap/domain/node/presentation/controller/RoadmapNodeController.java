package com.cowork.roadmap.domain.node.presentation.controller;

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
import com.cowork.roadmap.domain.node.service.RoadmapNodeService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Tag(name = "로드맵 노드", description = "로드맵 노드(문서)와 관련자료 구성 API")
@RestController
@RequestMapping("/roadmaps")
public class RoadmapNodeController {

    private final RoadmapNodeService nodeService;

    public RoadmapNodeController(RoadmapNodeService nodeService) {
        this.nodeService = nodeService;
    }

    @Operation(summary = "노드 생성")
    @PostMapping("/{roadmapId}/nodes")
    public Mono<ResponseEntity<NodeResDto>> createNode(
            @Parameter(hidden = true) @RequestHeader("X-User-Id") Long userId,
            @Parameter(hidden = true) @RequestHeader("X-User-Role") String userRole,
            @PathVariable Long roadmapId,
            @Valid @RequestBody CreateNodeReqDto request) {
        return nodeService.createNode(userId, userRole, roadmapId, request)
                .map(response -> ResponseEntity.status(HttpStatus.CREATED).body(response));
    }

    @Operation(summary = "형제 노드 순서 변경")
    @PutMapping("/{roadmapId}/nodes/reorder")
    public Mono<ResponseEntity<Void>> reorderNodes(@Parameter(hidden = true) @RequestHeader("X-User-Id") Long userId,
            @Parameter(hidden = true) @RequestHeader("X-User-Role") String userRole,
            @PathVariable Long roadmapId,
            @Valid @RequestBody ReorderNodesReqDto request) {
        return nodeService.reorderNodes(userId, userRole, roadmapId, request)
                .thenReturn(ResponseEntity.noContent().build());
    }

    @Operation(summary = "노드 상세 조회 (관련자료 포함)")
    @GetMapping("/nodes/{nodeId}")
    public Mono<NodeResDto> getNode(@Parameter(hidden = true) @RequestHeader("X-User-Id") Long userId,
            @Parameter(hidden = true) @RequestHeader("X-User-Role") String userRole,
            @PathVariable Long nodeId) {
        return nodeService.getNode(userId, userRole, nodeId);
    }

    @Operation(summary = "노드 수정")
    @PatchMapping("/nodes/{nodeId}")
    public Mono<NodeResDto> updateNode(@Parameter(hidden = true) @RequestHeader("X-User-Id") Long userId,
            @Parameter(hidden = true) @RequestHeader("X-User-Role") String userRole,
            @PathVariable Long nodeId,
            @Valid @RequestBody UpdateNodeReqDto request) {
        return nodeService.updateNode(userId, userRole, nodeId, request);
    }

    @Operation(summary = "노드 삭제 (하위 노드 포함)")
    @DeleteMapping("/nodes/{nodeId}")
    public Mono<ResponseEntity<Void>> deleteNode(@Parameter(hidden = true) @RequestHeader("X-User-Id") Long userId,
            @Parameter(hidden = true) @RequestHeader("X-User-Role") String userRole,
            @PathVariable Long nodeId) {
        return nodeService.deleteNode(userId, userRole, nodeId).thenReturn(ResponseEntity.noContent().build());
    }

    @Operation(summary = "노드 관련자료 목록")
    @GetMapping("/nodes/{nodeId}/references")
    public Flux<NodeReferenceResDto> listReferences(@Parameter(hidden = true) @RequestHeader("X-User-Id") Long userId,
            @Parameter(hidden = true) @RequestHeader("X-User-Role") String userRole,
            @PathVariable Long nodeId) {
        return nodeService.listReferences(userId, userRole, nodeId);
    }

    @Operation(summary = "노드 관련자료 추가")
    @PostMapping("/nodes/{nodeId}/references")
    public Mono<ResponseEntity<NodeReferenceResDto>> addReference(
            @Parameter(hidden = true) @RequestHeader("X-User-Id") Long userId,
            @Parameter(hidden = true) @RequestHeader("X-User-Role") String userRole,
            @PathVariable Long nodeId,
            @Valid @RequestBody NodeReferenceReqDto request) {
        return nodeService.addReference(userId, userRole, nodeId, request)
                .map(response -> ResponseEntity.status(HttpStatus.CREATED).body(response));
    }

    @Operation(summary = "관련자료 수정")
    @PatchMapping("/references/{referenceId}")
    public Mono<NodeReferenceResDto> updateReference(@Parameter(hidden = true) @RequestHeader("X-User-Id") Long userId,
            @Parameter(hidden = true) @RequestHeader("X-User-Role") String userRole,
            @PathVariable Long referenceId,
            @Valid @RequestBody NodeReferenceReqDto request) {
        return nodeService.updateReference(userId, userRole, referenceId, request);
    }

    @Operation(summary = "관련자료 삭제")
    @DeleteMapping("/references/{referenceId}")
    public Mono<ResponseEntity<Void>> deleteReference(@Parameter(hidden = true) @RequestHeader("X-User-Id") Long userId,
            @Parameter(hidden = true) @RequestHeader("X-User-Role") String userRole,
            @PathVariable Long referenceId) {
        return nodeService.deleteReference(userId, userRole, referenceId)
                .thenReturn(ResponseEntity.noContent().build());
    }
}
