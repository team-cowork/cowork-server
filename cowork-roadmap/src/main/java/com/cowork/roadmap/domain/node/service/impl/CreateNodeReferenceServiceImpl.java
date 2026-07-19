package com.cowork.roadmap.domain.node.service.impl;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cowork.roadmap.domain.node.entity.RoadmapNodeReference;
import com.cowork.roadmap.domain.node.presentation.data.request.NodeReferenceReqDto;
import com.cowork.roadmap.domain.node.presentation.data.response.NodeReferenceResDto;
import com.cowork.roadmap.domain.node.repository.RoadmapNodeReferenceRepository;
import com.cowork.roadmap.domain.node.service.CreateNodeReferenceService;
import com.cowork.roadmap.domain.node.service.support.RoadmapNodeLookupSupport;
import com.cowork.roadmap.domain.roadmap.service.RoadmapAccessGuard;
import com.cowork.roadmap.domain.roadmap.service.support.RoadmapLookupSupport;

import reactor.core.publisher.Mono;

@Service
public class CreateNodeReferenceServiceImpl implements CreateNodeReferenceService {

    private final RoadmapNodeReferenceRepository referenceRepository;
    private final RoadmapAccessGuard accessGuard;
    private final RoadmapLookupSupport roadmapLookupSupport;
    private final RoadmapNodeLookupSupport nodeLookupSupport;

    public CreateNodeReferenceServiceImpl(RoadmapNodeReferenceRepository referenceRepository,
            RoadmapAccessGuard accessGuard,
            RoadmapLookupSupport roadmapLookupSupport,
            RoadmapNodeLookupSupport nodeLookupSupport) {
        this.referenceRepository = referenceRepository;
        this.accessGuard = accessGuard;
        this.roadmapLookupSupport = roadmapLookupSupport;
        this.nodeLookupSupport = nodeLookupSupport;
    }

    @Override
    @Transactional
    public Mono<NodeReferenceResDto> execute(Long userId, String userRole, Long nodeId, NodeReferenceReqDto request) {
        return nodeLookupSupport.findNodeOrThrow(nodeId)
                .flatMap(node -> roadmapLookupSupport.findRoadmapOrThrow(node.getRoadmapId())
                        .flatMap(roadmap -> accessGuard.requireMutable(roadmap, userId, userRole)
                                .then(Mono.defer(() -> referenceRepository.countByNodeId(nodeId)))
                                .flatMap(count -> {
                                    RoadmapNodeReference ref = new RoadmapNodeReference();
                                    ref.setNodeId(nodeId);
                                    ref.setTitle(request.title());
                                    ref.setUrl(request.url());
                                    ref.setPosition(count.intValue());
                                    return referenceRepository.save(ref).map(NodeReferenceResDto::from);
                                })));
    }
}
