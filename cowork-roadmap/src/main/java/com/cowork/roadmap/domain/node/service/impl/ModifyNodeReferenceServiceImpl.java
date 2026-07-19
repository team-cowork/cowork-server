package com.cowork.roadmap.domain.node.service.impl;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cowork.roadmap.domain.node.presentation.data.request.NodeReferenceReqDto;
import com.cowork.roadmap.domain.node.presentation.data.response.NodeReferenceResDto;
import com.cowork.roadmap.domain.node.repository.RoadmapNodeReferenceRepository;
import com.cowork.roadmap.domain.node.service.ModifyNodeReferenceService;
import com.cowork.roadmap.domain.node.service.support.RoadmapNodeLookupSupport;
import com.cowork.roadmap.domain.roadmap.service.RoadmapAccessGuard;
import com.cowork.roadmap.domain.roadmap.service.support.RoadmapLookupSupport;

import reactor.core.publisher.Mono;

@Service
public class ModifyNodeReferenceServiceImpl implements ModifyNodeReferenceService {

    private final RoadmapNodeReferenceRepository referenceRepository;
    private final RoadmapAccessGuard accessGuard;
    private final RoadmapLookupSupport roadmapLookupSupport;
    private final RoadmapNodeLookupSupport nodeLookupSupport;

    public ModifyNodeReferenceServiceImpl(RoadmapNodeReferenceRepository referenceRepository,
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
    public Mono<NodeReferenceResDto> execute(Long userId,
            String userRole,
            Long referenceId,
            NodeReferenceReqDto request) {
        return nodeLookupSupport.findReferenceOrThrow(referenceId)
                .flatMap(ref -> nodeLookupSupport.findNodeOrThrow(ref.getNodeId())
                        .flatMap(node -> roadmapLookupSupport.findRoadmapOrThrow(node.getRoadmapId())
                                .flatMap(roadmap -> accessGuard.requireMutable(roadmap, userId, userRole)
                                        .then(Mono.defer(() -> {
                                            ref.setTitle(request.title());
                                            ref.setUrl(request.url());
                                            return referenceRepository.save(ref).map(NodeReferenceResDto::from);
                                        })))));
    }
}
