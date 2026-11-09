package com.cowork.roadmap.domain.node.service.impl;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cowork.roadmap.domain.node.presentation.data.request.ReorderNodesReqDto;
import com.cowork.roadmap.domain.node.repository.RoadmapNodeRepository;
import com.cowork.roadmap.domain.node.repository.projection.NodePositionProjection;
import com.cowork.roadmap.domain.node.service.ReorderRoadmapNodesService;
import com.cowork.roadmap.domain.roadmap.service.RoadmapAccessGuard;
import com.cowork.roadmap.domain.roadmap.service.support.RoadmapLookupSupport;

import lombok.RequiredArgsConstructor;
import team.themoment.sdk.exception.ExpectedException;

@Service
@RequiredArgsConstructor
public class ReorderRoadmapNodesServiceImpl implements ReorderRoadmapNodesService {

    private final RoadmapNodeRepository nodeRepository;
    private final RoadmapAccessGuard accessGuard;
    private final RoadmapLookupSupport roadmapLookupSupport;

    @Override
    @Transactional
    public void execute(Long userId, String userRole, Long roadmapId, ReorderNodesReqDto request) {
        var roadmap = roadmapLookupSupport.findRoadmapForUpdateOrThrow(roadmapId);
        accessGuard.requireMutable(roadmap, userId, userRole);
        List<NodePositionProjection> siblings = nodeRepository.findSiblingPositions(roadmapId, request.parentId());
        Set<Long> siblingIds = new HashSet<>();
        siblings.forEach(node -> siblingIds.add(node.getId()));
        Set<Long> requested = new HashSet<>(request.orderedNodeIds());
        if (requested.size() != request.orderedNodeIds().size() || !requested.equals(siblingIds)) {
            throw new ExpectedException("orderedNodeIds는 해당 부모의 모든 자식을 중복 없이 정확히 포함해야 합니다.", HttpStatus.BAD_REQUEST);
        }
        Map<Long, Integer> positions = new HashMap<>();
        siblings.forEach(node -> positions.put(node.getId(), node.getPosition()));
        List<Long> ordered = request.orderedNodeIds();
        Instant updatedAt = Instant.now();
        // 유일성 제약과 충돌하지 않도록 이동할 노드를 모두 임시 위치로 옮긴 뒤 최종 위치를 저장한다.
        for (int i = 0; i < ordered.size(); i++) {
            Long nodeId = ordered.get(i);
            if (positions.get(nodeId) != i) {
                nodeRepository.updatePosition(roadmapId, nodeId, -1 - i, userId, updatedAt);
            }
        }
        for (int i = 0; i < ordered.size(); i++) {
            nodeRepository.updatePosition(roadmapId, ordered.get(i), i, userId, updatedAt);
        }
    }
}
