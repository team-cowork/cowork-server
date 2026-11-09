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
        validateTotalContentLength(roadmapId, request.content());
        validateParent(roadmapId, request.parentId());
        int position = nextPosition(roadmapId, request.parentId());
        RoadmapNode node = RoadmapNode.builder()
                .roadmapId(roadmapId)
                .parentId(request.parentId())
                .title(request.title())
                .content(request.content())
                .sourceUrl(request.sourceUrl())
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

    /**
     * 로드맵 행 잠금 아래에서 합계를 읽으므로 동시 생성·수정도 상한을 넘지 않는다. 본문이 없으면 합계가 늘지 않으므로 확인하지 않는다.
     */
    private void validateTotalContentLength(Long roadmapId, String content) {
        int length = RoadmapNode.contentLength(content);
        if (length > 0 && nodeRepository.sumContentLengthByRoadmapId(roadmapId)
                + length > RoadmapNode.MAX_TOTAL_CONTENT_LENGTH_PER_ROADMAP) {
            throw new ExpectedException(
                    "로드맵 하나의 노드 본문은 합계 " + RoadmapNode.MAX_TOTAL_CONTENT_LENGTH_PER_ROADMAP + "자를 넘을 수 없습니다.",
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
    }

    private Integer nextPosition(Long roadmapId, Long parentId) {
        Long next = parentId == null
                ? nodeRepository.findNextRootPosition(roadmapId)
                : nodeRepository.findNextChildPosition(roadmapId, parentId);
        return next.intValue();
    }
}
