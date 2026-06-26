package com.cowork.roadmap.domain.roadmap.presentation.controller;

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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.cowork.roadmap.domain.roadmap.entity.RoadmapScope;
import com.cowork.roadmap.domain.roadmap.presentation.data.request.CreateRoadmapRequest;
import com.cowork.roadmap.domain.roadmap.presentation.data.request.UpdateRoadmapRequest;
import com.cowork.roadmap.domain.roadmap.presentation.data.response.RoadmapResponse;
import com.cowork.roadmap.domain.roadmap.presentation.data.response.RoadmapTreeResponse;
import com.cowork.roadmap.domain.roadmap.service.RoadmapService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Tag(name = "로드맵", description = "전공/포지션별 로드맵 생성·조회·구성 API")
@RestController
@RequestMapping("/roadmaps")
public class RoadmapController {

    private final RoadmapService roadmapService;

    public RoadmapController(RoadmapService roadmapService) {
        this.roadmapService = roadmapService;
    }

    @Operation(summary = "로드맵 생성")
    @PostMapping
    public Mono<ResponseEntity<RoadmapResponse>> createRoadmap(
            @Parameter(hidden = true) @RequestHeader("X-User-Id") Long userId,
            @Parameter(hidden = true) @RequestHeader("X-User-Role") String userRole,
            @Valid @RequestBody CreateRoadmapRequest request) {
        return roadmapService.createRoadmap(userId, userRole, request)
                .map(response -> ResponseEntity.status(HttpStatus.CREATED).body(response));
    }

    @Operation(summary = "로드맵 목록 조회", description = "teamId/projectId가 있으면 커스텀 로드맵, 없으면 scope(기본 GLOBAL) 기준 조회")
    @GetMapping
    public Flux<RoadmapResponse> listRoadmaps(@RequestParam(required = false) RoadmapScope scope,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Long teamId,
            @RequestParam(required = false) Long projectId) {
        return roadmapService.listRoadmaps(scope, category, teamId, projectId);
    }

    @Operation(summary = "로드맵 메타 조회")
    @GetMapping("/{roadmapId}")
    public Mono<RoadmapResponse> getRoadmap(@Parameter(hidden = true) @RequestHeader("X-User-Id") Long userId,
            @Parameter(hidden = true) @RequestHeader("X-User-Role") String userRole,
            @PathVariable Long roadmapId) {
        return roadmapService.getRoadmap(userId, userRole, roadmapId);
    }

    @Operation(summary = "로드맵 트리 조회", description = "노드(문서)와 관련자료가 중첩된 전체 트리를 반환")
    @GetMapping("/{roadmapId}/tree")
    public Mono<RoadmapTreeResponse> getRoadmapTree(@Parameter(hidden = true) @RequestHeader("X-User-Id") Long userId,
            @Parameter(hidden = true) @RequestHeader("X-User-Role") String userRole,
            @PathVariable Long roadmapId) {
        return roadmapService.getRoadmapTree(userId, userRole, roadmapId);
    }

    @Operation(summary = "로드맵 수정")
    @PatchMapping("/{roadmapId}")
    public Mono<RoadmapResponse> updateRoadmap(@Parameter(hidden = true) @RequestHeader("X-User-Id") Long userId,
            @Parameter(hidden = true) @RequestHeader("X-User-Role") String userRole,
            @PathVariable Long roadmapId,
            @Valid @RequestBody UpdateRoadmapRequest request) {
        return roadmapService.updateRoadmap(userId, userRole, roadmapId, request);
    }

    @Operation(summary = "로드맵 삭제")
    @DeleteMapping("/{roadmapId}")
    public Mono<ResponseEntity<Void>> deleteRoadmap(@Parameter(hidden = true) @RequestHeader("X-User-Id") Long userId,
            @Parameter(hidden = true) @RequestHeader("X-User-Role") String userRole,
            @PathVariable Long roadmapId) {
        return roadmapService.deleteRoadmap(userId, userRole, roadmapId).thenReturn(ResponseEntity.noContent().build());
    }
}
