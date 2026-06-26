package com.cowork.roadmap.domain.assignment.service.impl;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cowork.roadmap.domain.assignment.entity.RoadmapAssignment;
import com.cowork.roadmap.domain.assignment.repository.RoadmapAssignmentRepository;
import com.cowork.roadmap.domain.assignment.service.DeleteRoadmapAssignmentService;
import com.cowork.roadmap.domain.roadmap.service.RoadmapAccessGuard;

import reactor.core.publisher.Mono;
import team.themoment.sdk.exception.ExpectedException;

@Service
public class DeleteRoadmapAssignmentServiceImpl implements DeleteRoadmapAssignmentService {

    private static final String ROLE_ADMIN = "ADMIN";

    private final RoadmapAssignmentRepository assignmentRepository;
    private final RoadmapAccessGuard accessGuard;

    public DeleteRoadmapAssignmentServiceImpl(RoadmapAssignmentRepository assignmentRepository,
            RoadmapAccessGuard accessGuard) {
        this.assignmentRepository = assignmentRepository;
        this.accessGuard = accessGuard;
    }

    @Override
    @Transactional
    public Mono<Void> execute(Long userId, String userRole, Long assignmentId) {
        return findAssignmentOrThrow(assignmentId).flatMap(assignment -> {
            if (userId.equals(assignment.getAssignedBy()) || ROLE_ADMIN.equals(userRole)) {
                return assignmentRepository.delete(assignment);
            }
            return accessGuard.requireTeamManagerOrAdmin(userId, userRole, assignment.getTeamId())
                    .then(Mono.defer(() -> assignmentRepository.delete(assignment)));
        });
    }

    private Mono<RoadmapAssignment> findAssignmentOrThrow(Long assignmentId) {
        return assignmentRepository.findById(assignmentId)
                .switchIfEmpty(Mono.error(new ExpectedException("과제를 찾을 수 없습니다.", HttpStatus.NOT_FOUND)));
    }
}
