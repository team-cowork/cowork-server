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
        // shortcut: 프로젝트 투영이 없어 projectId의 소속 팀을 확인할 수 없으므로 PROJECT 할당을 막는다. 투영을 추가하면 팀
        // 일치 검증으로 바꾼다.
        if (request.scope() == RoadmapScope.PROJECT) {
            throw new ExpectedException("PROJECT 과제는 아직 할당할 수 없습니다.", HttpStatus.BAD_REQUEST);
        }
        Roadmap roadmap = roadmapLookupSupport.findRoadmapForUpdateOrThrow(request.roadmapId());
        validateAssignmentRoadmap(roadmap);
        accessGuard.requireReadable(roadmap, userId, userRole);
        accessGuard.requireTeamManagerOrAdmin(userId, userRole, request.teamId());
        validateNodeBelongsToRoadmap(request.nodeId(), request.roadmapId());
        validateAssignmentTeam(roadmap, request.teamId());
        accessGuard.requireAssignableMember(request.teamId(), request.assigneeUserId());
        validateNotAssigned(request);
        RoadmapAssignment assignment = RoadmapAssignment.builder()
                .roadmapId(request.roadmapId())
                .nodeId(request.nodeId())
                .scope(request.scope().name())
                .teamId(request.teamId())
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

    /** 담당자가 과제 팀 멤버이면 로드맵도 읽을 수 있도록 과제 팀을 로드맵 소유 팀으로 제한한다. */
    private void validateAssignmentTeam(Roadmap roadmap, Long teamId) {
        if (!teamId.equals(roadmap.getOwnerTeamId())) {
            throw new ExpectedException("과제 팀은 로드맵 소유 팀과 같아야 합니다.", HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * 과제 생성은 모두 로드맵 행 잠금 아래에서 실행되므로 잠금 이후 조회로 중복을 판정한다. node_key는 node_id가 NULL이면
     * 0인 생성 컬럼이다.
     */
    private void validateNotAssigned(CreateAssignmentReqDto request) {
        long nodeKey = request.nodeId() == null ? 0L : request.nodeId();
        if (assignmentRepository.countByRoadmapIdAndNodeKeyAndAssigneeUserIdForShare(request.roadmapId(),
                nodeKey,
                request.assigneeUserId()) > 0) {
            throw new ExpectedException("이미 같은 담당자에게 할당된 과제입니다.", HttpStatus.CONFLICT);
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
