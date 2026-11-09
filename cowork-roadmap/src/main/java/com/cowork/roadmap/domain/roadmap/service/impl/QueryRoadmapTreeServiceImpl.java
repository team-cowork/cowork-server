package com.cowork.roadmap.domain.roadmap.service.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cowork.roadmap.domain.node.entity.RoadmapNodeReference;
import com.cowork.roadmap.domain.node.presentation.data.response.NodeReferenceResDto;
import com.cowork.roadmap.domain.node.presentation.data.response.NodeTreeResDto;
import com.cowork.roadmap.domain.node.repository.RoadmapNodeReferenceRepository;
import com.cowork.roadmap.domain.node.repository.RoadmapNodeRepository;
import com.cowork.roadmap.domain.node.repository.projection.NodeTreeProjection;
import com.cowork.roadmap.domain.roadmap.presentation.data.response.RoadmapResDto;
import com.cowork.roadmap.domain.roadmap.presentation.data.response.RoadmapTreeResDto;
import com.cowork.roadmap.domain.roadmap.service.QueryRoadmapTreeService;
import com.cowork.roadmap.domain.roadmap.service.RoadmapAccessGuard;
import com.cowork.roadmap.domain.roadmap.service.support.RoadmapLookupSupport;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class QueryRoadmapTreeServiceImpl implements QueryRoadmapTreeService {

    private final RoadmapNodeRepository nodeRepository;
    private final RoadmapNodeReferenceRepository referenceRepository;
    private final RoadmapAccessGuard accessGuard;
    private final RoadmapLookupSupport lookupSupport;

    @Override
    @Transactional(readOnly = true)
    public RoadmapTreeResDto execute(Long userId, String userRole, Long roadmapId) {
        var roadmap = lookupSupport.findRoadmapOrThrow(roadmapId);
        accessGuard.requireReadable(roadmap, userId, userRole);
        List<NodeTreeProjection> nodes = nodeRepository.findTreeByRoadmapId(roadmapId);
        if (nodes.isEmpty()) {
            return new RoadmapTreeResDto(RoadmapResDto.from(roadmap), List.of());
        }
        List<Long> nodeIds = nodes.stream().map(NodeTreeProjection::getId).toList();
        List<RoadmapNodeReference> refs = referenceRepository.findByNodeIdInOrderByNodeIdAscPositionAsc(nodeIds);
        return new RoadmapTreeResDto(RoadmapResDto.from(roadmap), assembleTree(nodes, refs));
    }

    private List<NodeTreeResDto> assembleTree(List<NodeTreeProjection> nodes, List<RoadmapNodeReference> refs) {
        List<NodeTreeProjection> roots = new ArrayList<>();
        Map<Long, List<NodeTreeProjection>> childrenByParent = new HashMap<>();
        for (NodeTreeProjection node : nodes) {
            if (node.getParentId() == null) {
                roots.add(node);
            } else {
                childrenByParent.computeIfAbsent(node.getParentId(), key -> new ArrayList<>()).add(node);
            }
        }

        Map<Long, List<NodeReferenceResDto>> refsByNode = new HashMap<>();
        for (RoadmapNodeReference ref : refs) {
            refsByNode.computeIfAbsent(ref.getNodeId(), key -> new ArrayList<>()).add(NodeReferenceResDto.from(ref));
        }

        return roots.stream().map(root -> buildSubtree(root, childrenByParent, refsByNode)).toList();
    }

    private NodeTreeResDto buildSubtree(NodeTreeProjection node,
            Map<Long, List<NodeTreeProjection>> childrenByParent,
            Map<Long, List<NodeReferenceResDto>> refsByNode) {
        List<NodeTreeResDto> children = childrenByParent.getOrDefault(node.getId(), List.of())
                .stream()
                .map(child -> buildSubtree(child, childrenByParent, refsByNode))
                .toList();
        return new NodeTreeResDto(node.getId(),
                node.getParentId(),
                node.getTitle(),
                node.getSourceUrl(),
                node.getSourceTitle(),
                node.getPosition(),
                refsByNode.getOrDefault(node.getId(), List.of()),
                children);
    }
}
