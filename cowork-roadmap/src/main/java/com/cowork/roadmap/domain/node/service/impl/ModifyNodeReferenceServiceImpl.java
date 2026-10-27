package com.cowork.roadmap.domain.node.service.impl;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cowork.roadmap.domain.node.entity.RoadmapNodeReference;
import com.cowork.roadmap.domain.node.presentation.data.request.NodeReferenceReqDto;
import com.cowork.roadmap.domain.node.presentation.data.response.NodeReferenceResDto;
import com.cowork.roadmap.domain.node.repository.RoadmapNodeReferenceRepository;
import com.cowork.roadmap.domain.node.service.ModifyNodeReferenceService;
import com.cowork.roadmap.domain.node.service.support.RoadmapNodeLookupSupport;
import com.cowork.roadmap.domain.roadmap.service.RoadmapAccessGuard;
import com.cowork.roadmap.domain.roadmap.service.support.RoadmapLookupSupport;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ModifyNodeReferenceServiceImpl implements ModifyNodeReferenceService {

    private final RoadmapNodeReferenceRepository referenceRepository;
    private final RoadmapAccessGuard accessGuard;
    private final RoadmapLookupSupport roadmapLookupSupport;
    private final RoadmapNodeLookupSupport nodeLookupSupport;

    @Override
    @Transactional
    public NodeReferenceResDto execute(Long userId, String userRole, Long referenceId, NodeReferenceReqDto request) {
        var ref = nodeLookupSupport.findReferenceOrThrow(referenceId);
        var node = nodeLookupSupport.findNodeOrThrow(ref.getNodeId());
        var roadmap = roadmapLookupSupport.findRoadmapOrThrow(node.getRoadmapId());
        accessGuard.requireMutable(roadmap, userId, userRole);
        RoadmapNodeReference updated = ref.toBuilder().title(request.title()).url(request.url()).build();
        updated.prepareUpdateFrom(ref);
        return NodeReferenceResDto.from(referenceRepository.saveAndFlush(updated));
    }
}
