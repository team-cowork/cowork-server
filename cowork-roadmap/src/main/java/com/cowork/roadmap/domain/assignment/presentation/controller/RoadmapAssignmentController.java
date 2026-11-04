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
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
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
    @ApiResponses({@ApiResponse(responseCode = "201", description = "출제 성공"),
            @ApiResponse(responseCode = "400", description = "필수 값 누락, 할당 범위가 GLOBAL, PROJECT 할당에 projectId 없음, 글로벌 로드맵, 노드가 로드맵에 속하지 않음, 팀이 로드맵 소유 팀과 다름, 담당자가 팀의 활성 멤버가 아님"),
            @ApiResponse(responseCode = "403", description = "로드맵 조회 권한 또는 팀 OWNER/ADMIN 권한 없음"),
            @ApiResponse(responseCode = "404", description = "로드맵 또는 노드 없음"),
            @ApiResponse(responseCode = "409", description = "같은 로드맵, 노드, 담당자의 과제가 이미 있음"),
            @ApiResponse(responseCode = "503", description = "팀 멤버 투영 동기화 중")})
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
