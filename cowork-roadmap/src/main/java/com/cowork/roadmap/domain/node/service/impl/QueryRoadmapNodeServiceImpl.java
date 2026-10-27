package com.cowork.roadmap.domain.node.service.impl;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cowork.roadmap.domain.node.presentation.data.response.NodeResDto;
import com.cowork.roadmap.domain.node.service.QueryRoadmapNodeService;
import com.cowork.roadmap.domain.node.service.support.RoadmapNodeLookupSupport;
import com.cowork.roadmap.domain.roadmap.service.RoadmapAccessGuard;
import com.cowork.roadmap.domain.roadmap.service.support.RoadmapLookupSupport;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class QueryRoadmapNodeServiceImpl implements QueryRoadmapNodeService {

    private final RoadmapAccessGuard accessGuard;
    private final RoadmapLookupSupport roadmapLookupSupport;
    private final RoadmapNodeLookupSupport nodeLookupSupport;

    @Override
    @Transactional(readOnly = true)
    public NodeResDto execute(Long userId, String userRole, Long nodeId) {
        var node = nodeLookupSupport.findNodeOrThrow(nodeId);
        var roadmap = roadmapLookupSupport.findRoadmapOrThrow(node.getRoadmapId());
        accessGuard.requireReadable(roadmap, userId, userRole);
        return NodeResDto.of(node, nodeLookupSupport.loadReferences(nodeId));
    }
}
