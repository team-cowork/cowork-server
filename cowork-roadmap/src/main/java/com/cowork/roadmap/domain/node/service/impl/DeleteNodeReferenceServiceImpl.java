package com.cowork.roadmap.domain.node.service.impl;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cowork.roadmap.domain.node.repository.RoadmapNodeReferenceRepository;
import com.cowork.roadmap.domain.node.service.DeleteNodeReferenceService;
import com.cowork.roadmap.domain.node.service.support.RoadmapNodeLookupSupport;
import com.cowork.roadmap.domain.roadmap.service.RoadmapAccessGuard;
import com.cowork.roadmap.domain.roadmap.service.support.RoadmapLookupSupport;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class DeleteNodeReferenceServiceImpl implements DeleteNodeReferenceService {

    private final RoadmapNodeReferenceRepository roadmapNodeReferenceRepository;
    private final RoadmapAccessGuard roadmapAccessGuard;
    private final RoadmapLookupSupport roadmapLookupSupport;
    private final RoadmapNodeLookupSupport roadmapNodeLookupSupport;

    @Override
    @Transactional
    public void execute(Long userId, Long referenceId) {
        var ref = roadmapNodeLookupSupport.findReferenceOrThrow(referenceId);
        var node = roadmapNodeLookupSupport.findNodeOrThrow(ref.getNodeId());
        var roadmap = roadmapLookupSupport.findRoadmapOrThrow(node.getRoadmapId());
        roadmapAccessGuard.requireMutable(roadmap, userId);
        roadmapNodeReferenceRepository.delete(ref);
    }
}
