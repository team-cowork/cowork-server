package com.cowork.roadmap.domain.node.service.impl;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cowork.roadmap.domain.node.entity.RoadmapNode;
import com.cowork.roadmap.domain.node.presentation.data.request.CreateNodeReqDto;
import com.cowork.roadmap.domain.node.presentation.data.response.NodeResDto;
import com.cowork.roadmap.domain.node.repository.RoadmapNodeRepository;
import com.cowork.roadmap.domain.node.service.CreateRoadmapNodeService;
import com.cowork.roadmap.domain.roadmap.service.RoadmapAccessGuard;
import com.cowork.roadmap.domain.roadmap.service.support.RoadmapLookupSupport;

import lombok.RequiredArgsConstructor;
import team.themoment.sdk.exception.ExpectedException;

@Service
@RequiredArgsConstructor
public class CreateRoadmapNodeServiceImpl implements CreateRoadmapNodeService {

    private final RoadmapNodeRepository nodeRepository;
    private final RoadmapAccessGuard accessGuard;
    private final RoadmapLookupSupport roadmapLookupSupport;

    @Override
    @Transactional
    public NodeResDto execute(Long userId, String userRole, Long roadmapId, CreateNodeReqDto request) {
        var roadmap = roadmapLookupSupport.findRoadmapForUpdateOrThrow(roadmapId);
        accessGuard.requireMutable(roadmap, userId, userRole);
        validateNodeCount(roadmapId);
        validateParent(roadmapId, request.parentId());
        int position = nextPosition(roadmapId, request.parentId());
        RoadmapNode node = RoadmapNode.builder()
                .roadmapId(roadmapId)
                .parentId(request.parentId())
                .title(request.title())
                .content(request.content())
                .sourceUrl(blankToNull(request.sourceUrl()))
                .sourceTitle(request.sourceTitle())
                .position(position)
                .build();
        node.setCreatedBy(userId);
        node.setLastModifiedBy(userId);
        return NodeResDto.of(nodeRepository.saveAndFlush(node), List.of());
    }

    /** 로드맵 행 잠금 아래에서 세므로 동시 생성도 상한을 넘지 않는다. */
    private void validateNodeCount(Long roadmapId) {
        if (nodeRepository.countByRoadmapId(roadmapId) >= RoadmapNode.MAX_NODES_PER_ROADMAP) {
            throw new ExpectedException("로드맵 하나에는 노드를 최대 " + RoadmapNode.MAX_NODES_PER_ROADMAP + "개까지 만들 수 있습니다.",
                    HttpStatus.BAD_REQUEST);
        }
    }

    private void validateParent(Long roadmapId, Long parentId) {
        if (parentId == null) {
            return;
        }
        RoadmapNode parent = nodeRepository.findByIdForShare(parentId)
                .orElseThrow(() -> new ExpectedException("상위 노드를 찾을 수 없습니다.", HttpStatus.NOT_FOUND));
        if (!roadmapId.equals(parent.getRoadmapId())) {
            throw new ExpectedException("상위 노드가 같은 로드맵에 속하지 않습니다.", HttpStatus.BAD_REQUEST);
        }
        validateDepth(parent);
    }

    /** 노드의 상위 노드는 바뀌지 않고 로드맵 행 잠금 아래에서 세므로, 동시 생성도 깊이 상한을 넘지 않는다. */
    private void validateDepth(RoadmapNode parent) {
        // 상위 노드가 루트면 새 노드는 2단계이고, 상위 노드의 조상 하나마다 한 단계씩 깊어진다.
        int depth = 2;
        for (Long ancestorId = parent.getParentId(); ancestorId != null
                && depth <= RoadmapNode.MAX_TREE_DEPTH; ancestorId = nodeRepository.findParentIdById(ancestorId)
                        .orElse(null)) {
            depth++;
        }
        if (depth > RoadmapNode.MAX_TREE_DEPTH) {
            throw new ExpectedException("노드는 최대 " + RoadmapNode.MAX_TREE_DEPTH + "단계까지 만들 수 있습니다.",
                    HttpStatus.BAD_REQUEST);
        }
    }

    private Integer nextPosition(Long roadmapId, Long parentId) {
        Long next = parentId == null
                ? nodeRepository.findNextRootPosition(roadmapId)
                : nodeRepository.findNextChildPosition(roadmapId, parentId);
        return next.intValue();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
