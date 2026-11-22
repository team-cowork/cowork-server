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

    private final RoadmapAccessGuard roadmapAccessGuard;
    private final RoadmapLookupSupport roadmapLookupSupport;
    private final RoadmapNodeLookupSupport roadmapNodeLookupSupport;

    @Override
    @Transactional(readOnly = true)
    public NodeResDto execute(Long userId, Long nodeId) {
        var node = roadmapNodeLookupSupport.findNodeOrThrow(nodeId);
        var roadmap = roadmapLookupSupport.findRoadmapOrThrow(node.getRoadmapId());
        roadmapAccessGuard.requireReadable(roadmap, userId);
        return NodeResDto.of(node, roadmapNodeLookupSupport.loadReferences(nodeId));
    }
}
