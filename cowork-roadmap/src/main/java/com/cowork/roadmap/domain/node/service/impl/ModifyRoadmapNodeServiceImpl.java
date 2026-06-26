package com.cowork.roadmap.domain.node.service.impl;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cowork.roadmap.domain.node.presentation.data.request.UpdateNodeReqDto;
import com.cowork.roadmap.domain.node.presentation.data.response.NodeResDto;
import com.cowork.roadmap.domain.node.repository.RoadmapNodeRepository;
import com.cowork.roadmap.domain.node.service.ModifyRoadmapNodeService;
import com.cowork.roadmap.domain.node.service.support.RoadmapNodeLookupSupport;
import com.cowork.roadmap.domain.roadmap.service.RoadmapAccessGuard;
import com.cowork.roadmap.domain.roadmap.service.support.RoadmapLookupSupport;

import reactor.core.publisher.Mono;

@Service
public class ModifyRoadmapNodeServiceImpl implements ModifyRoadmapNodeService {

    private final RoadmapNodeRepository nodeRepository;
    private final RoadmapAccessGuard accessGuard;
    private final RoadmapLookupSupport roadmapLookupSupport;
    private final RoadmapNodeLookupSupport nodeLookupSupport;

    public ModifyRoadmapNodeServiceImpl(RoadmapNodeRepository nodeRepository,
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
    public Mono<NodeResDto> execute(Long userId, String userRole, Long nodeId, UpdateNodeReqDto request) {
        return nodeLookupSupport.findNodeOrThrow(nodeId)
                .flatMap(node -> roadmapLookupSupport.findRoadmapOrThrow(node.getRoadmapId())
                        .flatMap(
                                roadmap -> accessGuard.requireMutable(roadmap, userId, userRole).then(Mono.defer(() -> {
                                    if (request.title() != null) {
                                        node.setTitle(request.title());
                                    }
                                    if (request.content() != null) {
                                        node.setContent(request.content());
                                    }
                                    if (request.sourceUrl() != null) {
                                        node.setSourceUrl(request.sourceUrl());
                                    }
                                    if (request.sourceTitle() != null) {
                                        node.setSourceTitle(request.sourceTitle());
                                    }
                                    node.setLastModifiedBy(userId);
                                    return nodeRepository.save(node);
                                }))
                                        .flatMap(saved -> nodeLookupSupport.loadReferences(saved.getId())
                                                .map(refs -> NodeResDto.of(saved, refs)))));
    }
}
