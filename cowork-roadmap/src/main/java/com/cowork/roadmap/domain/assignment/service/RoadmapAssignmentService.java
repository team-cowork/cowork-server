package com.cowork.roadmap.domain.assignment.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cowork.roadmap.domain.assignment.entity.AssignmentStatus;
import com.cowork.roadmap.domain.assignment.entity.RoadmapAssignment;
import com.cowork.roadmap.domain.assignment.presentation.data.request.CreateAssignmentReqDto;
import com.cowork.roadmap.domain.assignment.presentation.data.request.UpdateAssignmentStatusReqDto;
import com.cowork.roadmap.domain.assignment.presentation.data.response.AssignmentResDto;
import com.cowork.roadmap.domain.assignment.repository.RoadmapAssignmentRepository;
import com.cowork.roadmap.domain.node.repository.RoadmapNodeRepository;
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
    private final RoadmapNodeRepository nodeRepository;
    private final RoadmapAccessGuard accessGuard;

    public RoadmapAssignmentService(RoadmapAssignmentRepository assignmentRepository,
            RoadmapRepository roadmapRepository,
            RoadmapNodeRepository nodeRepository,
            RoadmapAccessGuard accessGuard) {
        this.assignmentRepository = assignmentRepository;
        this.roadmapRepository = roadmapRepository;
        this.nodeRepository = nodeRepository;
        this.accessGuard = accessGuard;
    }

    @Transactional
    public Mono<AssignmentResDto> createAssignment(Long userId, String userRole, CreateAssignmentReqDto request) {
        if (request.scope() == RoadmapScope.GLOBAL) {
            return Mono.error(new ExpectedException("과제 할당 범위는 TEAM 또는 PROJECT여야 합니다.", HttpStatus.BAD_REQUEST));
        }
        if (request.scope() == RoadmapScope.PROJECT && request.projectId() == null) {
            return Mono.error(new ExpectedException("PROJECT 할당에는 projectId가 필요합니다.", HttpStatus.BAD_REQUEST));
        }

        return roadmapRepository.findById(request.roadmapId())
                .switchIfEmpty(Mono.error(new ExpectedException("로드맵을 찾을 수 없습니다.", HttpStatus.NOT_FOUND)))
                .flatMap(roadmap -> accessGuard.requireReadable(roadmap, userId, userRole)
                        .then(accessGuard.requireTeamManagerOrAdmin(userId, userRole, request.teamId()))
                        .then(validateNodeBelongsToRoadmap(request.nodeId(), request.roadmapId()))
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
                            return assignmentRepository.save(assignment).map(AssignmentResDto::from);
                        })));
    }

    private Mono<Void> validateNodeBelongsToRoadmap(Long nodeId, Long roadmapId) {
        if (nodeId == null) {
            return Mono.empty();
        }
        return nodeRepository.findById(nodeId)
                .switchIfEmpty(Mono.error(new ExpectedException("노드를 찾을 수 없습니다.", HttpStatus.NOT_FOUND)))
                .flatMap(node -> roadmapId.equals(node.getRoadmapId())
                        ? Mono.empty()
                        : Mono.error(new ExpectedException("노드가 해당 로드맵에 속하지 않습니다.", HttpStatus.BAD_REQUEST)))
                .then();
    }

    public Flux<AssignmentResDto> listMyAssignments(Long userId) {
        return assignmentRepository.findByAssigneeUserIdOrderByIdDesc(userId).map(AssignmentResDto::from);
    }

    public Flux<AssignmentResDto> listByRoadmap(Long userId, String userRole, Long roadmapId) {
        return roadmapRepository.findById(roadmapId)
                .switchIfEmpty(Mono.error(new ExpectedException("로드맵을 찾을 수 없습니다.", HttpStatus.NOT_FOUND)))
                .flatMapMany(roadmap -> accessGuard.requireReadable(roadmap, userId, userRole)
                        .thenMany(assignmentRepository.findByRoadmapIdOrderByIdDesc(roadmapId)
                                .map(AssignmentResDto::from)));
    }

    @Transactional
    public Mono<AssignmentResDto> updateStatus(Long userId,
            String userRole,
            Long assignmentId,
            UpdateAssignmentStatusReqDto request) {
        return findAssignmentOrThrow(assignmentId).flatMap(assignment -> {
            boolean allowed = userId.equals(assignment.getAssigneeUserId()) || userId.equals(assignment.getAssignedBy())
                    || ROLE_ADMIN.equals(userRole);
            if (!allowed) {
                return Mono.error(new ExpectedException("과제 상태를 변경할 권한이 없습니다.", HttpStatus.FORBIDDEN));
            }
            assignment.setStatus(request.status().name());
            return assignmentRepository.save(assignment).map(AssignmentResDto::from);
        });
    }

    @Transactional
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
                .switchIfEmpty(Mono.error(new ExpectedException("과제를 찾을 수 없습니다.", HttpStatus.NOT_FOUND)));
    }
}
