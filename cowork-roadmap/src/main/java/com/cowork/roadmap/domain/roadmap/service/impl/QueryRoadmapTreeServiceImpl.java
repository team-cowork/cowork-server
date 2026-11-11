package com.cowork.roadmap.domain.roadmap.service.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cowork.roadmap.domain.node.entity.RoadmapNode;
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
import team.themoment.sdk.exception.ExpectedException;

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

        return roots.stream().map(root -> buildSubtree(root, 1, childrenByParent, refsByNode)).toList();
    }

    /** 깊이 상한이 생기기 전에 만들어진 트리는 상한보다 깊을 수 있어, 직렬화 깊이 한도로 실패하기 전에 거부한다. */
    private NodeTreeResDto buildSubtree(NodeTreeProjection node,
            int depth,
            Map<Long, List<NodeTreeProjection>> childrenByParent,
            Map<Long, List<NodeReferenceResDto>> refsByNode) {
        if (depth > RoadmapNode.MAX_TREE_DEPTH) {
            throw new ExpectedException("노드 트리가 최대 " + RoadmapNode.MAX_TREE_DEPTH + "단계를 넘어 조회할 수 없습니다.",
                    HttpStatus.CONFLICT);
        }
        List<NodeTreeResDto> children = childrenByParent.getOrDefault(node.getId(), List.of())
                .stream()
                .map(child -> buildSubtree(child, depth + 1, childrenByParent, refsByNode))
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
