package com.cowork.roadmap.domain.roadmap.service.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cowork.roadmap.domain.node.entity.RoadmapNode;
import com.cowork.roadmap.domain.node.entity.RoadmapNodeReference;
import com.cowork.roadmap.domain.node.presentation.data.response.NodeReferenceResDto;
import com.cowork.roadmap.domain.node.presentation.data.response.NodeTreeResDto;
import com.cowork.roadmap.domain.node.repository.RoadmapNodeReferenceRepository;
import com.cowork.roadmap.domain.node.repository.RoadmapNodeRepository;
import com.cowork.roadmap.domain.roadmap.presentation.data.response.RoadmapResDto;
import com.cowork.roadmap.domain.roadmap.presentation.data.response.RoadmapTreeResDto;
import com.cowork.roadmap.domain.roadmap.service.QueryRoadmapTreeService;
import com.cowork.roadmap.domain.roadmap.service.RoadmapAccessGuard;
import com.cowork.roadmap.domain.roadmap.service.support.RoadmapLookupSupport;

import reactor.core.publisher.Mono;

@Service
public class QueryRoadmapTreeServiceImpl implements QueryRoadmapTreeService {

    private final RoadmapNodeRepository nodeRepository;
    private final RoadmapNodeReferenceRepository referenceRepository;
    private final RoadmapAccessGuard accessGuard;
    private final RoadmapLookupSupport lookupSupport;

    public QueryRoadmapTreeServiceImpl(RoadmapNodeRepository nodeRepository,
            RoadmapNodeReferenceRepository referenceRepository,
            RoadmapAccessGuard accessGuard,
            RoadmapLookupSupport lookupSupport) {
        this.nodeRepository = nodeRepository;
        this.referenceRepository = referenceRepository;
        this.accessGuard = accessGuard;
        this.lookupSupport = lookupSupport;
    }

    @Override
    @Transactional(readOnly = true)
    public Mono<RoadmapTreeResDto> execute(Long userId, String userRole, Long roadmapId) {
        return lookupSupport.findRoadmapOrThrow(roadmapId)
                .flatMap(roadmap -> accessGuard.requireReadable(roadmap, userId, userRole)
                        .then(Mono
                                .defer(() -> nodeRepository.findByRoadmapIdOrderByPositionAsc(roadmapId).collectList()))
                        .flatMap(nodes -> {
                            if (nodes.isEmpty()) {
                                return Mono.just(new RoadmapTreeResDto(RoadmapResDto.from(roadmap), List.of()));
                            }
                            List<Long> nodeIds = nodes.stream().map(RoadmapNode::getId).toList();
                            return referenceRepository.findByNodeIdInOrderByNodeIdAscPositionAsc(nodeIds)
                                    .collectList()
                                    .map(refs -> new RoadmapTreeResDto(RoadmapResDto.from(roadmap),
                                            assembleTree(nodes, refs)));
                        }));
    }

    private List<NodeTreeResDto> assembleTree(List<RoadmapNode> nodes, List<RoadmapNodeReference> refs) {
        List<RoadmapNode> roots = new ArrayList<>();
        Map<Long, List<RoadmapNode>> childrenByParent = new HashMap<>();
        for (RoadmapNode node : nodes) {
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

    private NodeTreeResDto buildSubtree(RoadmapNode node,
            Map<Long, List<RoadmapNode>> childrenByParent,
            Map<Long, List<NodeReferenceResDto>> refsByNode) {
        List<NodeTreeResDto> children = childrenByParent.getOrDefault(node.getId(), List.of())
                .stream()
                .map(child -> buildSubtree(child, childrenByParent, refsByNode))
                .toList();
        return new NodeTreeResDto(node.getId(),
                node.getParentId(),
                node.getTitle(),
                node.getContent(),
                node.getSourceUrl(),
                node.getSourceTitle(),
                node.getPosition(),
                refsByNode.getOrDefault(node.getId(), List.of()),
                children);
    }
}
