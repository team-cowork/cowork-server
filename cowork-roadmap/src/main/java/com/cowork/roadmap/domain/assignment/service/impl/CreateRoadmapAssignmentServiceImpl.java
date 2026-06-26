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
import com.cowork.roadmap.domain.roadmap.entity.RoadmapScope;
import com.cowork.roadmap.domain.roadmap.service.RoadmapAccessGuard;
import com.cowork.roadmap.domain.roadmap.service.support.RoadmapLookupSupport;

import reactor.core.publisher.Mono;
import team.themoment.sdk.exception.ExpectedException;

@Service
public class CreateRoadmapAssignmentServiceImpl implements CreateRoadmapAssignmentService {

    private final RoadmapAssignmentRepository assignmentRepository;
    private final RoadmapNodeRepository nodeRepository;
    private final RoadmapAccessGuard accessGuard;
    private final RoadmapLookupSupport roadmapLookupSupport;

    public CreateRoadmapAssignmentServiceImpl(RoadmapAssignmentRepository assignmentRepository,
            RoadmapNodeRepository nodeRepository,
            RoadmapAccessGuard accessGuard,
            RoadmapLookupSupport roadmapLookupSupport) {
        this.assignmentRepository = assignmentRepository;
        this.nodeRepository = nodeRepository;
        this.accessGuard = accessGuard;
        this.roadmapLookupSupport = roadmapLookupSupport;
    }

    @Override
    @Transactional
    public Mono<AssignmentResDto> execute(Long userId, String userRole, CreateAssignmentReqDto request) {
        if (request.scope() == RoadmapScope.GLOBAL) {
            return Mono.error(new ExpectedException("과제 할당 범위는 TEAM 또는 PROJECT여야 합니다.", HttpStatus.BAD_REQUEST));
        }
        if (request.scope() == RoadmapScope.PROJECT && request.projectId() == null) {
            return Mono.error(new ExpectedException("PROJECT 할당에는 projectId가 필요합니다.", HttpStatus.BAD_REQUEST));
        }

        return roadmapLookupSupport.findRoadmapOrThrow(request.roadmapId())
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
}
