package com.cowork.roadmap.domain.node.service.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cowork.roadmap.domain.node.entity.RoadmapNode;
import com.cowork.roadmap.domain.node.presentation.data.request.ReorderNodesReqDto;
import com.cowork.roadmap.domain.node.repository.RoadmapNodeRepository;
import com.cowork.roadmap.domain.node.service.ReorderRoadmapNodesService;
import com.cowork.roadmap.domain.roadmap.service.RoadmapAccessGuard;
import com.cowork.roadmap.domain.roadmap.service.support.RoadmapLookupSupport;

import reactor.core.publisher.Mono;
import team.themoment.sdk.exception.ExpectedException;

@Service
public class ReorderRoadmapNodesServiceImpl implements ReorderRoadmapNodesService {

    private final RoadmapNodeRepository nodeRepository;
    private final RoadmapAccessGuard accessGuard;
    private final RoadmapLookupSupport roadmapLookupSupport;

    public ReorderRoadmapNodesServiceImpl(RoadmapNodeRepository nodeRepository,
            RoadmapAccessGuard accessGuard,
            RoadmapLookupSupport roadmapLookupSupport) {
        this.nodeRepository = nodeRepository;
        this.accessGuard = accessGuard;
        this.roadmapLookupSupport = roadmapLookupSupport;
    }

    @Override
    @Transactional
    public Mono<Void> execute(Long userId, String userRole, Long roadmapId, ReorderNodesReqDto request) {
        return roadmapLookupSupport.findRoadmapOrThrow(roadmapId)
                .flatMap(roadmap -> accessGuard.requireMutable(roadmap, userId, userRole)
                        .then(nodeRepository.findByRoadmapIdOrderByPositionAsc(roadmapId).collectList())
                        .flatMap(all -> {
                            List<RoadmapNode> siblings = all.stream()
                                    .filter(node -> Objects.equals(node.getParentId(), request.parentId()))
                                    .toList();
                            Set<Long> siblingIds = new HashSet<>();
                            siblings.forEach(node -> siblingIds.add(node.getId()));
                            Set<Long> requested = new HashSet<>(request.orderedNodeIds());
                            if (requested.size() != request.orderedNodeIds().size() || !requested.equals(siblingIds)) {
                                return Mono.error(
                                        new ExpectedException("orderedNodeIds는 해당 부모의 모든 자식을 중복 없이 정확히 포함해야 합니다.",
                                                HttpStatus.BAD_REQUEST));
                            }
                            Map<Long, RoadmapNode> byId = new HashMap<>();
                            siblings.forEach(node -> byId.put(node.getId(), node));
                            List<RoadmapNode> updated = new ArrayList<>();
                            List<Long> ordered = request.orderedNodeIds();
                            for (int i = 0; i < ordered.size(); i++) {
                                RoadmapNode node = byId.get(ordered.get(i));
                                node.setPosition(i);
                                node.setLastModifiedBy(userId);
                                updated.add(node);
                            }
                            return nodeRepository.saveAll(updated).then();
                        }));
    }
}
