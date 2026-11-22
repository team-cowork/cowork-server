package com.cowork.roadmap.domain.node.service.impl;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cowork.roadmap.domain.node.presentation.data.response.NodeReferenceResDto;
import com.cowork.roadmap.domain.node.service.ListNodeReferencesService;
import com.cowork.roadmap.domain.node.service.support.RoadmapNodeLookupSupport;
import com.cowork.roadmap.domain.roadmap.service.RoadmapAccessGuard;
import com.cowork.roadmap.domain.roadmap.service.support.RoadmapLookupSupport;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ListNodeReferencesServiceImpl implements ListNodeReferencesService {

    private final RoadmapAccessGuard roadmapAccessGuard;
    private final RoadmapLookupSupport roadmapLookupSupport;
    private final RoadmapNodeLookupSupport roadmapNodeLookupSupport;

    @Override
    @Transactional(readOnly = true)
    public List<NodeReferenceResDto> execute(Long userId, Long nodeId) {
        var node = roadmapNodeLookupSupport.findNodeOrThrow(nodeId);
        var roadmap = roadmapLookupSupport.findRoadmapOrThrow(node.getRoadmapId());
        roadmapAccessGuard.requireReadable(roadmap, userId);
        return roadmapNodeLookupSupport.loadReferences(nodeId);
    }
}
