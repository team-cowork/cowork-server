package com.cowork.roadmap.domain.assignment.service.impl;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cowork.roadmap.domain.assignment.entity.RoadmapAssignment;
import com.cowork.roadmap.domain.assignment.repository.RoadmapAssignmentRepository;
import com.cowork.roadmap.domain.assignment.service.DeleteRoadmapAssignmentService;
import com.cowork.roadmap.domain.roadmap.service.RoadmapAccessGuard;

import lombok.RequiredArgsConstructor;
import team.themoment.sdk.exception.ExpectedException;

@Service
@RequiredArgsConstructor
public class DeleteRoadmapAssignmentServiceImpl implements DeleteRoadmapAssignmentService {

    private final RoadmapAssignmentRepository assignmentRepository;
    private final RoadmapAccessGuard accessGuard;

    @Override
    @Transactional
    public void execute(Long userId, Long assignmentId) {
        RoadmapAssignment assignment = findAssignmentOrThrow(assignmentId);
        if (!accessGuard.accountAllows(userId, "delete_team_assignments")) {
            if (userId.equals(assignment.getAssignedBy())) {
                accessGuard.requireTeamMember(assignment.getTeamId(), userId);
            } else {
                accessGuard.requireTeamManager(userId, assignment.getTeamId());
            }
        }
        assignmentRepository.delete(assignment);
    }

    private RoadmapAssignment findAssignmentOrThrow(Long assignmentId) {
        return assignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new ExpectedException("과제를 찾을 수 없습니다.", HttpStatus.NOT_FOUND));
    }
}
