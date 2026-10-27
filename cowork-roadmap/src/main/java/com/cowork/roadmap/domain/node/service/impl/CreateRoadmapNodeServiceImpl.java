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
