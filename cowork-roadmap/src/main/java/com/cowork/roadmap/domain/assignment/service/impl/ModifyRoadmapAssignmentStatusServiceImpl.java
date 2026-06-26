package com.cowork.roadmap.domain.assignment.service.impl;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cowork.roadmap.domain.assignment.entity.RoadmapAssignment;
import com.cowork.roadmap.domain.assignment.presentation.data.request.UpdateAssignmentStatusReqDto;
import com.cowork.roadmap.domain.assignment.presentation.data.response.AssignmentResDto;
import com.cowork.roadmap.domain.assignment.repository.RoadmapAssignmentRepository;
import com.cowork.roadmap.domain.assignment.service.ModifyRoadmapAssignmentStatusService;

import reactor.core.publisher.Mono;
import team.themoment.sdk.exception.ExpectedException;

@Service
public class ModifyRoadmapAssignmentStatusServiceImpl implements ModifyRoadmapAssignmentStatusService {

    private static final String ROLE_ADMIN = "ADMIN";

    private final RoadmapAssignmentRepository assignmentRepository;

    public ModifyRoadmapAssignmentStatusServiceImpl(RoadmapAssignmentRepository assignmentRepository) {
        this.assignmentRepository = assignmentRepository;
    }

    @Override
    @Transactional
    public Mono<AssignmentResDto> execute(Long userId,
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

    private Mono<RoadmapAssignment> findAssignmentOrThrow(Long assignmentId) {
        return assignmentRepository.findById(assignmentId)
                .switchIfEmpty(Mono.error(new ExpectedException("과제를 찾을 수 없습니다.", HttpStatus.NOT_FOUND)));
    }
}
