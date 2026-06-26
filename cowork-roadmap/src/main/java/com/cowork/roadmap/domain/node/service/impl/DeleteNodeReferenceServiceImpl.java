package com.cowork.roadmap.domain.node.service.impl;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cowork.roadmap.domain.node.repository.RoadmapNodeReferenceRepository;
import com.cowork.roadmap.domain.node.service.DeleteNodeReferenceService;
import com.cowork.roadmap.domain.node.service.support.RoadmapNodeLookupSupport;
import com.cowork.roadmap.domain.roadmap.service.RoadmapAccessGuard;
import com.cowork.roadmap.domain.roadmap.service.support.RoadmapLookupSupport;

import reactor.core.publisher.Mono;

@Service
public class DeleteNodeReferenceServiceImpl implements DeleteNodeReferenceService {

    private final RoadmapNodeReferenceRepository referenceRepository;
    private final RoadmapAccessGuard accessGuard;
    private final RoadmapLookupSupport roadmapLookupSupport;
    private final RoadmapNodeLookupSupport nodeLookupSupport;

    public DeleteNodeReferenceServiceImpl(RoadmapNodeReferenceRepository referenceRepository,
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
    public Mono<Void> execute(Long userId, String userRole, Long referenceId) {
        return nodeLookupSupport.findReferenceOrThrow(referenceId)
                .flatMap(ref -> nodeLookupSupport.findNodeOrThrow(ref.getNodeId())
                        .flatMap(node -> roadmapLookupSupport.findRoadmapOrThrow(node.getRoadmapId())
                                .flatMap(roadmap -> accessGuard.requireMutable(roadmap, userId, userRole)
                                        .then(referenceRepository.delete(ref)))));
    }
}
