package com.cowork.roadmap.domain.assignment.service.impl;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cowork.roadmap.domain.assignment.entity.RoadmapAssignment;
import com.cowork.roadmap.domain.assignment.presentation.data.request.UpdateAssignmentStatusReqDto;
import com.cowork.roadmap.domain.assignment.presentation.data.response.AssignmentResDto;
import com.cowork.roadmap.domain.assignment.repository.RoadmapAssignmentRepository;
import com.cowork.roadmap.domain.assignment.service.ModifyRoadmapAssignmentStatusService;

import lombok.RequiredArgsConstructor;
import team.themoment.sdk.exception.ExpectedException;

@Service
@RequiredArgsConstructor
public class ModifyRoadmapAssignmentStatusServiceImpl implements ModifyRoadmapAssignmentStatusService {

    private static final String ROLE_ADMIN = "ADMIN";

    private final RoadmapAssignmentRepository assignmentRepository;

    @Override
    @Transactional
    public AssignmentResDto execute(Long userId,
            String userRole,
            Long assignmentId,
            UpdateAssignmentStatusReqDto request) {
        RoadmapAssignment assignment = findAssignmentOrThrow(assignmentId);
        boolean allowed = userId.equals(assignment.getAssigneeUserId()) || userId.equals(assignment.getAssignedBy())
                || ROLE_ADMIN.equals(userRole);
        if (!allowed) {
            throw new ExpectedException("과제 상태를 변경할 권한이 없습니다.", HttpStatus.FORBIDDEN);
        }
        RoadmapAssignment updated = assignment.toBuilder().status(request.status().name()).build();
        updated.prepareUpdateFrom(assignment);
        return AssignmentResDto.from(assignmentRepository.saveAndFlush(updated));
    }

    private RoadmapAssignment findAssignmentOrThrow(Long assignmentId) {
        return assignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new ExpectedException("과제를 찾을 수 없습니다.", HttpStatus.NOT_FOUND));
    }
}
