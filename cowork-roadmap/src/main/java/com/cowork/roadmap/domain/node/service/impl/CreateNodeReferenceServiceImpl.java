package com.cowork.roadmap.domain.node.service.impl;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.cowork.roadmap.domain.node.entity.RoadmapNodeReference;
import com.cowork.roadmap.domain.node.presentation.data.request.NodeReferenceReqDto;
import com.cowork.roadmap.domain.node.presentation.data.response.NodeReferenceResDto;
import com.cowork.roadmap.domain.node.repository.RoadmapNodeReferenceRepository;
import com.cowork.roadmap.domain.node.service.CreateNodeReferenceService;
import com.cowork.roadmap.domain.node.service.support.RoadmapNodeLookupSupport;
import com.cowork.roadmap.domain.roadmap.service.RoadmapAccessGuard;
import com.cowork.roadmap.domain.roadmap.service.support.RoadmapLookupSupport;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class CreateNodeReferenceServiceImpl implements CreateNodeReferenceService {

    private final RoadmapNodeReferenceRepository referenceRepository;
    private final RoadmapAccessGuard accessGuard;
    private final RoadmapLookupSupport roadmapLookupSupport;
    private final RoadmapNodeLookupSupport nodeLookupSupport;

    /**
     * 로드맵 잠금 전에 노드를 읽으므로 READ COMMITTED로 실행해, 잠금을 기다리는 동안 커밋된 참고 자료·노드 삭제를 잠금 이후
     * 조회가 보게 한다.
     */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Mono<NodeReferenceResDto> execute(Long userId, String userRole, Long nodeId, NodeReferenceReqDto request) {
        return nodeLookupSupport.findNodeOrThrow(nodeId)
                .flatMap(node -> roadmapLookupSupport.findRoadmapForUpdateOrThrow(node.getRoadmapId())
                        .flatMap(roadmap -> accessGuard.requireMutable(roadmap, userId, userRole)
                                .then(nodeLookupSupport.findNodeOrThrow(nodeId))
                                .then(Mono.defer(() -> referenceRepository.findNextPosition(nodeId)))
                                .flatMap(position -> {
                                    RoadmapNodeReference ref = RoadmapNodeReference.builder()
                                            .nodeId(nodeId)
                                            .title(request.title())
                                            .url(request.url())
                                            .position(position.intValue())
                                            .build();
                                    return referenceRepository.save(ref).map(NodeReferenceResDto::from);
                                })));
    }
}
