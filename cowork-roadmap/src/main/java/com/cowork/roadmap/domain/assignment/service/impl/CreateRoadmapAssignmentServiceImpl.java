package com.cowork.roadmap.domain.assignment.service.impl;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cowork.roadmap.domain.assignment.entity.AssignmentStatus;
import com.cowork.roadmap.domain.assignment.entity.RoadmapAssignment;
import com.cowork.roadmap.domain.assignment.presentation.data.request.CreateAssignmentReqDto;
import com.cowork.roadmap.domain.assignment.presentation.data.response.AssignmentResDto;
import com.cowork.roadmap.domain.assignment.repository.RoadmapAssignmentRepository;
import com.cowork.roadmap.domain.assignment.service.CreateRoadmapAssignmentService;
import com.cowork.roadmap.domain.node.repository.RoadmapNodeRepository;
import com.cowork.roadmap.domain.roadmap.entity.Roadmap;
import com.cowork.roadmap.domain.roadmap.entity.RoadmapScope;
import com.cowork.roadmap.domain.roadmap.service.RoadmapAccessGuard;
import com.cowork.roadmap.domain.roadmap.service.support.RoadmapLookupSupport;

import lombok.RequiredArgsConstructor;
import team.themoment.sdk.exception.ExpectedException;

@Service
@RequiredArgsConstructor
public class CreateRoadmapAssignmentServiceImpl implements CreateRoadmapAssignmentService {

    private final RoadmapAssignmentRepository assignmentRepository;
    private final RoadmapNodeRepository nodeRepository;
    private final RoadmapAccessGuard accessGuard;
    private final RoadmapLookupSupport roadmapLookupSupport;

    @Override
    @Transactional
    public AssignmentResDto execute(Long userId, String userRole, CreateAssignmentReqDto request) {
        if (request.scope() == RoadmapScope.GLOBAL) {
            throw new ExpectedException("과제 할당 범위는 TEAM 또는 PROJECT여야 합니다.", HttpStatus.BAD_REQUEST);
        }
        if (request.scope() == RoadmapScope.PROJECT && request.projectId() == null) {
            throw new ExpectedException("PROJECT 할당에는 projectId가 필요합니다.", HttpStatus.BAD_REQUEST);
        }
        Roadmap roadmap = roadmapLookupSupport.findRoadmapForUpdateOrThrow(request.roadmapId());
        validateAssignmentRoadmap(roadmap);
        accessGuard.requireReadable(roadmap, userId, userRole);
        accessGuard.requireTeamManagerOrAdmin(userId, userRole, request.teamId());
        validateNodeBelongsToRoadmap(request.nodeId(), request.roadmapId());
        RoadmapAssignment assignment = RoadmapAssignment.builder()
                .roadmapId(request.roadmapId())
                .nodeId(request.nodeId())
                .scope(request.scope().name())
                .teamId(request.teamId())
                .projectId(request.scope() == RoadmapScope.PROJECT ? request.projectId() : null)
                .assigneeUserId(request.assigneeUserId())
                .assignedBy(userId)
                .status(AssignmentStatus.ASSIGNED.name())
                .dueDate(request.dueDate())
                .build();
        return AssignmentResDto.from(assignmentRepository.saveAndFlush(assignment));
    }

    private void validateAssignmentRoadmap(Roadmap roadmap) {
        if (RoadmapScope.GLOBAL.name().equals(roadmap.getScope())) {
            throw new ExpectedException("글로벌 로드맵에는 과제를 연결할 수 없습니다.", HttpStatus.BAD_REQUEST);
        }
    }

    private void validateNodeBelongsToRoadmap(Long nodeId, Long roadmapId) {
        if (nodeId == null) {
            return;
        }
        var node = nodeRepository.findByIdForShare(nodeId)
                .orElseThrow(() -> new ExpectedException("노드를 찾을 수 없습니다.", HttpStatus.NOT_FOUND));
        if (!roadmapId.equals(node.getRoadmapId())) {
            throw new ExpectedException("노드가 해당 로드맵에 속하지 않습니다.", HttpStatus.BAD_REQUEST);
        }
    }
}
