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

    private final RoadmapNodeReferenceRepository referenceRepository;
    private final RoadmapAccessGuard accessGuard;
    private final RoadmapLookupSupport roadmapLookupSupport;
    private final RoadmapNodeLookupSupport nodeLookupSupport;

    @Override
    @Transactional
    public void execute(Long userId, String userRole, Long referenceId) {
        var ref = nodeLookupSupport.findReferenceOrThrow(referenceId);
        var node = nodeLookupSupport.findNodeOrThrow(ref.getNodeId());
        var roadmap = roadmapLookupSupport.findRoadmapOrThrow(node.getRoadmapId());
        accessGuard.requireMutable(roadmap, userId, userRole);
        referenceRepository.delete(ref);
    }
}
