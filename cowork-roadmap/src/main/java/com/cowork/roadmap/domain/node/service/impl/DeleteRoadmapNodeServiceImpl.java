package com.cowork.roadmap.domain.node.service.impl;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cowork.roadmap.domain.assignment.repository.RoadmapAssignmentRepository;
import com.cowork.roadmap.domain.node.entity.RoadmapNode;
import com.cowork.roadmap.domain.node.repository.RoadmapNodeRepository;
import com.cowork.roadmap.domain.node.service.DeleteRoadmapNodeService;
import com.cowork.roadmap.domain.node.service.support.RoadmapNodeLookupSupport;
import com.cowork.roadmap.domain.roadmap.service.RoadmapAccessGuard;
import com.cowork.roadmap.domain.roadmap.service.support.RoadmapLookupSupport;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;
import team.themoment.sdk.exception.ExpectedException;

@Service
@RequiredArgsConstructor
public class DeleteRoadmapNodeServiceImpl implements DeleteRoadmapNodeService {

    private final RoadmapNodeRepository nodeRepository;
    private final RoadmapAssignmentRepository assignmentRepository;
    private final RoadmapAccessGuard accessGuard;
    private final RoadmapLookupSupport roadmapLookupSupport;
    private final RoadmapNodeLookupSupport nodeLookupSupport;

    @Override
    @Transactional
    public Mono<Void> execute(Long userId, String userRole, Long nodeId) {
        return nodeLookupSupport.findNodeOrThrow(nodeId)
                .flatMap(node -> roadmapLookupSupport.findRoadmapOrThrow(node.getRoadmapId())
                        .flatMap(roadmap -> accessGuard.requireMutable(roadmap, userId, userRole)
                                .then(Mono.defer(() -> nodeRepository.findAllByRoadmapIdForUpdate(node.getRoadmapId())
                                        .collectList()))
                                .flatMap(all -> deleteSubtree(node.getRoadmapId(), collectSubtreeIds(nodeId, all)))));
    }

    /**
     * 과제가 연결된 노드는 진행 기록을 보존하기 위해 삭제를 거부한다. 서브트리는 노드를 잠근 읽기 결과로 계산해 하위 노드·과제 생성의 노드
     * 공유 잠금과 직렬화하고, 잠금 읽기로 과제 수를 확인한다.
     */
    private Mono<Void> deleteSubtree(Long roadmapId, Set<Long> subtreeIds) {
        return assignmentRepository.countByRoadmapIdAndNodeIdInForShare(roadmapId, subtreeIds)
                .flatMap(assignmentCount -> assignmentCount > 0
                        ? Mono.error(new ExpectedException("연결된 과제 " + assignmentCount + "건을 먼저 삭제해야 노드를 삭제할 수 있습니다.",
                                HttpStatus.CONFLICT))
                        : nodeRepository.deleteAllById(subtreeIds));
    }

    private Set<Long> collectSubtreeIds(Long rootId, List<RoadmapNode> all) {
        Map<Long, List<Long>> childrenByParent = new HashMap<>();
        for (RoadmapNode node : all) {
            if (node.getParentId() != null) {
                childrenByParent.computeIfAbsent(node.getParentId(), key -> new ArrayList<>()).add(node.getId());
            }
        }
        Set<Long> result = new HashSet<>();
        Deque<Long> queue = new ArrayDeque<>();
        queue.add(rootId);
        while (!queue.isEmpty()) {
            Long current = queue.poll();
            if (result.add(current)) {
                queue.addAll(childrenByParent.getOrDefault(current, List.of()));
            }
        }
        return result;
    }
}
