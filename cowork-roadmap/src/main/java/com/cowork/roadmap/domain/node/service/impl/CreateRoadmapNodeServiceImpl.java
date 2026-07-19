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

import reactor.core.publisher.Mono;
import team.themoment.sdk.exception.ExpectedException;

@Service
public class CreateRoadmapNodeServiceImpl implements CreateRoadmapNodeService {

    private final RoadmapNodeRepository nodeRepository;
    private final RoadmapAccessGuard accessGuard;
    private final RoadmapLookupSupport roadmapLookupSupport;

    public CreateRoadmapNodeServiceImpl(RoadmapNodeRepository nodeRepository,
            RoadmapAccessGuard accessGuard,
            RoadmapLookupSupport roadmapLookupSupport) {
        this.nodeRepository = nodeRepository;
        this.accessGuard = accessGuard;
        this.roadmapLookupSupport = roadmapLookupSupport;
    }

    @Override
    @Transactional
    public Mono<NodeResDto> execute(Long userId, String userRole, Long roadmapId, CreateNodeReqDto request) {
        return roadmapLookupSupport.findRoadmapOrThrow(roadmapId)
                .flatMap(roadmap -> accessGuard.requireMutable(roadmap, userId, userRole)
                        .then(validateParent(roadmapId, request.parentId()))
                        .then(nextPosition(roadmapId, request.parentId()))
                        .flatMap(position -> {
                            RoadmapNode node = new RoadmapNode();
                            node.setRoadmapId(roadmapId);
                            node.setParentId(request.parentId());
                            node.setTitle(request.title());
                            node.setContent(request.content());
                            node.setSourceUrl(request.sourceUrl());
                            node.setSourceTitle(request.sourceTitle());
                            node.setPosition(position);
                            node.setCreatedBy(userId);
                            node.setLastModifiedBy(userId);
                            return nodeRepository.save(node).map(saved -> NodeResDto.of(saved, List.of()));
                        }));
    }

    private Mono<Void> validateParent(Long roadmapId, Long parentId) {
        if (parentId == null) {
            return Mono.empty();
        }
        return nodeRepository.findById(parentId)
                .switchIfEmpty(Mono.error(new ExpectedException("상위 노드를 찾을 수 없습니다.", HttpStatus.NOT_FOUND)))
                .flatMap(parent -> roadmapId.equals(parent.getRoadmapId())
                        ? Mono.empty()
                        : Mono.error(new ExpectedException("상위 노드가 같은 로드맵에 속하지 않습니다.", HttpStatus.BAD_REQUEST)))
                .then();
    }

    private Mono<Integer> nextPosition(Long roadmapId, Long parentId) {
        Mono<Long> count = parentId == null
                ? nodeRepository.countByRoadmapIdAndParentIdIsNull(roadmapId)
                : nodeRepository.countByRoadmapIdAndParentId(roadmapId, parentId);
        return count.map(Long::intValue);
    }
}
