package com.cowork.roadmap.domain.assignment.presentation.controller;

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

import com.cowork.roadmap.domain.assignment.presentation.data.request.CreateAssignmentRequest;
import com.cowork.roadmap.domain.assignment.presentation.data.request.UpdateAssignmentStatusRequest;
import com.cowork.roadmap.domain.assignment.presentation.data.response.AssignmentResponse;
import com.cowork.roadmap.domain.assignment.service.RoadmapAssignmentService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Tag(name = "로드맵 과제", description = "온보딩 과제 출제·진도 관리 API")
@RestController
@RequestMapping("/roadmaps")
public class RoadmapAssignmentController {

    private final RoadmapAssignmentService assignmentService;

    public RoadmapAssignmentController(RoadmapAssignmentService assignmentService) {
        this.assignmentService = assignmentService;
    }

    @Operation(summary = "과제 출제")
    @PostMapping("/assignments")
    public Mono<ResponseEntity<AssignmentResponse>> createAssignment(
            @Parameter(hidden = true) @RequestHeader("X-User-Id") Long userId,
            @Parameter(hidden = true) @RequestHeader("X-User-Role") String userRole,
            @Valid @RequestBody CreateAssignmentRequest request) {
        return assignmentService.createAssignment(userId, userRole, request)
                .map(response -> ResponseEntity.status(HttpStatus.CREATED).body(response));
    }

    @Operation(summary = "내 과제 목록")
    @GetMapping("/assignments/me")
    public Flux<AssignmentResponse> listMyAssignments(
            @Parameter(hidden = true) @RequestHeader("X-User-Id") Long userId) {
        return assignmentService.listMyAssignments(userId);
    }

    @Operation(summary = "로드맵별 과제 목록")
    @GetMapping("/{roadmapId}/assignments")
    public Flux<AssignmentResponse> listByRoadmap(@Parameter(hidden = true) @RequestHeader("X-User-Id") Long userId,
            @Parameter(hidden = true) @RequestHeader("X-User-Role") String userRole,
            @PathVariable Long roadmapId) {
        return assignmentService.listByRoadmap(userId, userRole, roadmapId);
    }

    @Operation(summary = "과제 진행 상태 변경")
    @PatchMapping("/assignments/{assignmentId}/status")
    public Mono<AssignmentResponse> updateStatus(@Parameter(hidden = true) @RequestHeader("X-User-Id") Long userId,
            @Parameter(hidden = true) @RequestHeader("X-User-Role") String userRole,
            @PathVariable Long assignmentId,
            @Valid @RequestBody UpdateAssignmentStatusRequest request) {
        return assignmentService.updateStatus(userId, userRole, assignmentId, request);
    }

    @Operation(summary = "과제 삭제")
    @DeleteMapping("/assignments/{assignmentId}")
    public Mono<ResponseEntity<Void>> deleteAssignment(
            @Parameter(hidden = true) @RequestHeader("X-User-Id") Long userId,
            @Parameter(hidden = true) @RequestHeader("X-User-Role") String userRole,
            @PathVariable Long assignmentId) {
        return assignmentService.deleteAssignment(userId, userRole, assignmentId)
                .thenReturn(ResponseEntity.noContent().build());
    }
}
