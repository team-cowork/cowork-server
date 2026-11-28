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

    private final RoadmapAssignmentRepository roadmapAssignmentRepository;
    private final RoadmapAccessGuard roadmapAccessGuard;

    @Override
    @Transactional
    public void execute(Long userId, Long assignmentId) {
        RoadmapAssignment assignment = findAssignmentOrThrow(assignmentId);
        if (!roadmapAccessGuard.accountAllows(userId, "delete_team_assignments")) {
            if (userId.equals(assignment.getAssignedBy())) {
                roadmapAccessGuard.requireTeamMember(assignment.getTeamId(), userId);
            } else {
                roadmapAccessGuard.requireRoadmapManager(userId, assignment.getTeamId());
            }
        }
        roadmapAssignmentRepository.delete(assignment);
    }

    private RoadmapAssignment findAssignmentOrThrow(Long assignmentId) {
        return roadmapAssignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new ExpectedException("과제를 찾을 수 없습니다.", HttpStatus.NOT_FOUND));
    }
}
