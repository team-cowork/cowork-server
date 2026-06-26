package com.cowork.roadmap.domain.assignment.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.cowork.roadmap.domain.assignment.entity.AssignmentStatus;
import com.cowork.roadmap.domain.assignment.entity.RoadmapAssignment;
import com.cowork.roadmap.domain.assignment.presentation.data.request.CreateAssignmentRequest;
import com.cowork.roadmap.domain.assignment.presentation.data.request.UpdateAssignmentStatusRequest;
import com.cowork.roadmap.domain.assignment.presentation.data.response.AssignmentResponse;
import com.cowork.roadmap.domain.assignment.repository.RoadmapAssignmentRepository;
import com.cowork.roadmap.domain.roadmap.entity.RoadmapScope;
import com.cowork.roadmap.domain.roadmap.repository.RoadmapRepository;
import com.cowork.roadmap.domain.roadmap.service.RoadmapAccessGuard;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import team.themoment.sdk.exception.ExpectedException;

@Service
public class RoadmapAssignmentService {

    private static final String ROLE_ADMIN = "ADMIN";

    private final RoadmapAssignmentRepository assignmentRepository;
    private final RoadmapRepository roadmapRepository;
    private final RoadmapAccessGuard accessGuard;

    public RoadmapAssignmentService(RoadmapAssignmentRepository assignmentRepository,
            RoadmapRepository roadmapRepository,
            RoadmapAccessGuard accessGuard) {
        this.assignmentRepository = assignmentRepository;
        this.roadmapRepository = roadmapRepository;
        this.accessGuard = accessGuard;
    }

    public Mono<AssignmentResponse> createAssignment(Long userId, String userRole, CreateAssignmentRequest request) {
        if (request.scope() == RoadmapScope.GLOBAL) {
            return Mono.error(new ExpectedException("과제 할당 범위는 TEAM 또는 PROJECT여야 합니다.", HttpStatus.BAD_REQUEST));
        }
        if (request.scope() == RoadmapScope.PROJECT && request.projectId() == null) {
            return Mono.error(new ExpectedException("PROJECT 할당에는 projectId가 필요합니다.", HttpStatus.BAD_REQUEST));
        }

        return roadmapRepository.findById(request.roadmapId())
                .switchIfEmpty(Mono.error(
                        new ExpectedException("로드맵을 찾을 수 없습니다. id=" + request.roadmapId(), HttpStatus.NOT_FOUND)))
                .flatMap(roadmap -> accessGuard.requireTeamManagerOrAdmin(userId, userRole, request.teamId())
                        .then(Mono.defer(() -> {
                            RoadmapAssignment assignment = new RoadmapAssignment();
                            assignment.setRoadmapId(request.roadmapId());
                            assignment.setNodeId(request.nodeId());
                            assignment.setScope(request.scope().name());
                            assignment.setTeamId(request.teamId());
                            assignment
                                    .setProjectId(request.scope() == RoadmapScope.PROJECT ? request.projectId() : null);
                            assignment.setAssigneeUserId(request.assigneeUserId());
                            assignment.setAssignedBy(userId);
                            assignment.setStatus(AssignmentStatus.ASSIGNED.name());
                            assignment.setDueDate(request.dueDate());
                            return assignmentRepository.save(assignment).map(AssignmentResponse::from);
                        })));
    }

    public Flux<AssignmentResponse> listMyAssignments(Long userId) {
        return assignmentRepository.findByAssigneeUserIdOrderByIdDesc(userId).map(AssignmentResponse::from);
    }

    public Flux<AssignmentResponse> listByRoadmap(Long userId, String userRole, Long roadmapId) {
        return roadmapRepository.findById(roadmapId)
                .switchIfEmpty(
                        Mono.error(new ExpectedException("로드맵을 찾을 수 없습니다. id=" + roadmapId, HttpStatus.NOT_FOUND)))
                .flatMapMany(roadmap -> accessGuard.requireReadable(roadmap, userId, userRole)
                        .thenMany(assignmentRepository.findByRoadmapIdOrderByIdDesc(roadmapId)
                                .map(AssignmentResponse::from)));
    }

    public Mono<AssignmentResponse> updateStatus(Long userId,
            String userRole,
            Long assignmentId,
            UpdateAssignmentStatusRequest request) {
        return findAssignmentOrThrow(assignmentId).flatMap(assignment -> {
            boolean allowed = userId.equals(assignment.getAssigneeUserId()) || userId.equals(assignment.getAssignedBy())
                    || ROLE_ADMIN.equals(userRole);
            if (!allowed) {
                return Mono.error(new ExpectedException("과제 상태를 변경할 권한이 없습니다.", HttpStatus.FORBIDDEN));
            }
            assignment.setStatus(request.status().name());
            return assignmentRepository.save(assignment).map(AssignmentResponse::from);
        });
    }

    public Mono<Void> deleteAssignment(Long userId, String userRole, Long assignmentId) {
        return findAssignmentOrThrow(assignmentId).flatMap(assignment -> {
            if (userId.equals(assignment.getAssignedBy()) || ROLE_ADMIN.equals(userRole)) {
                return assignmentRepository.delete(assignment);
            }
            return accessGuard.requireTeamManagerOrAdmin(userId, userRole, assignment.getTeamId())
                    .then(assignmentRepository.delete(assignment));
        });
    }

    private Mono<RoadmapAssignment> findAssignmentOrThrow(Long assignmentId) {
        return assignmentRepository.findById(assignmentId)
                .switchIfEmpty(
                        Mono.error(new ExpectedException("과제를 찾을 수 없습니다. id=" + assignmentId, HttpStatus.NOT_FOUND)));
    }
}
