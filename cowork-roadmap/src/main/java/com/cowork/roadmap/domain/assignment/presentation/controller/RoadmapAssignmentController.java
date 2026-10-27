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
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@Tag(name = "로드맵 과제", description = "온보딩 과제 출제·진도 관리 API")
@RestController
@RequestMapping("/roadmaps")
@RequiredArgsConstructor
public class RoadmapAssignmentController {

    private final CreateRoadmapAssignmentService createRoadmapAssignmentService;
    private final ListMyRoadmapAssignmentsService listMyRoadmapAssignmentsService;
    private final ListRoadmapAssignmentsService listRoadmapAssignmentsService;
    private final ModifyRoadmapAssignmentStatusService modifyRoadmapAssignmentStatusService;
    private final DeleteRoadmapAssignmentService deleteRoadmapAssignmentService;

    @Operation(summary = "과제 출제")
    @PostMapping("/assignments")
    public ResponseEntity<AssignmentResDto> createAssignment(
            @Parameter(hidden = true) @RequestHeader("X-User-Id") Long userId,
            @Parameter(hidden = true) @RequestHeader("X-User-Role") String userRole,
            @Valid @RequestBody CreateAssignmentReqDto request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(createRoadmapAssignmentService.execute(userId, userRole, request));
    }

    @Operation(summary = "내 과제 목록")
    @GetMapping("/assignments/me")
    public List<AssignmentResDto> listMyAssignments(@Parameter(hidden = true) @RequestHeader("X-User-Id") Long userId) {
        return listMyRoadmapAssignmentsService.execute(userId);
    }

    @Operation(summary = "로드맵별 과제 목록")
    @GetMapping("/{roadmapId}/assignments")
    public List<AssignmentResDto> listByRoadmap(@Parameter(hidden = true) @RequestHeader("X-User-Id") Long userId,
            @Parameter(hidden = true) @RequestHeader("X-User-Role") String userRole,
            @PathVariable Long roadmapId) {
        return listRoadmapAssignmentsService.execute(userId, userRole, roadmapId);
    }

    @Operation(summary = "과제 진행 상태 변경")
    @PatchMapping("/assignments/{assignmentId}/status")
    public AssignmentResDto updateStatus(@Parameter(hidden = true) @RequestHeader("X-User-Id") Long userId,
            @Parameter(hidden = true) @RequestHeader("X-User-Role") String userRole,
            @PathVariable Long assignmentId,
            @Valid @RequestBody UpdateAssignmentStatusReqDto request) {
        return modifyRoadmapAssignmentStatusService.execute(userId, userRole, assignmentId, request);
    }

    @Operation(summary = "과제 삭제")
    @DeleteMapping("/assignments/{assignmentId}")
    public ResponseEntity<Void> deleteAssignment(@Parameter(hidden = true) @RequestHeader("X-User-Id") Long userId,
            @Parameter(hidden = true) @RequestHeader("X-User-Role") String userRole,
            @PathVariable Long assignmentId) {
        deleteRoadmapAssignmentService.execute(userId, userRole, assignmentId);
        return ResponseEntity.noContent().build();
    }
}
