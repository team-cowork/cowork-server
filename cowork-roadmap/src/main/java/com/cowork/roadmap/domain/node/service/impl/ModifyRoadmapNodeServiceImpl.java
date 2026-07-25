package com.cowork.roadmap.domain.node.service.impl;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cowork.roadmap.domain.node.entity.RoadmapNode;
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
                                    RoadmapNode updated = node.toBuilder()
                                            .title(request.title() != null ? request.title() : node.getTitle())
                                            .content(request.content() != null ? request.content() : node.getContent())
                                            .sourceUrl(request.sourceUrl() != null
                                                    ? request.sourceUrl()
                                                    : node.getSourceUrl())
                                            .sourceTitle(request.sourceTitle() != null
                                                    ? request.sourceTitle()
                                                    : node.getSourceTitle())
                                            .build();
                                    updated.copyAuditFrom(node);
                                    updated.setLastModifiedBy(userId);
                                    return nodeRepository.save(updated);
                                }))
                                        .flatMap(saved -> nodeLookupSupport.loadReferences(saved.getId())
                                                .map(refs -> NodeResDto.of(saved, refs)))));
    }
}
