package com.cowork.roadmap.domain.node.service.impl;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cowork.roadmap.domain.node.entity.RoadmapNode;
import com.cowork.roadmap.domain.node.repository.RoadmapNodeRepository;
import com.cowork.roadmap.domain.node.service.DeleteRoadmapNodeService;
import com.cowork.roadmap.domain.node.service.support.RoadmapNodeLookupSupport;
import com.cowork.roadmap.domain.roadmap.service.RoadmapAccessGuard;
import com.cowork.roadmap.domain.roadmap.service.support.RoadmapLookupSupport;

import reactor.core.publisher.Mono;

@Service
public class DeleteRoadmapNodeServiceImpl implements DeleteRoadmapNodeService {

    private final RoadmapNodeRepository nodeRepository;
    private final RoadmapAccessGuard accessGuard;
    private final RoadmapLookupSupport roadmapLookupSupport;
    private final RoadmapNodeLookupSupport nodeLookupSupport;

    public DeleteRoadmapNodeServiceImpl(RoadmapNodeRepository nodeRepository,
            RoadmapAccessGuard accessGuard,
            RoadmapLookupSupport roadmapLookupSupport,
            RoadmapNodeLookupSupport nodeLookupSupport) {
        this.nodeRepository = nodeRepository;
        this.accessGuard = accessGuard;
        this.roadmapLookupSupport = roadmapLookupSupport;
        this.nodeLookupSupport = nodeLookupSupport;
    }

    @Override
    @Transactional
    public Mono<Void> execute(Long userId, String userRole, Long nodeId) {
        return nodeLookupSupport.findNodeOrThrow(nodeId)
                .flatMap(
                        node -> roadmapLookupSupport.findRoadmapOrThrow(node.getRoadmapId())
                                .flatMap(roadmap -> accessGuard.requireMutable(roadmap, userId, userRole)
                                        .then(Mono.defer(() -> nodeRepository
                                                .findByRoadmapIdOrderByPositionAsc(node.getRoadmapId())
                                                .collectList()))
                                        .flatMap(all -> nodeRepository.deleteAllById(collectSubtreeIds(nodeId, all)))));
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
