package com.cowork.roadmap.domain.node.service.impl;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.cowork.roadmap.domain.node.entity.RoadmapNode;
import com.cowork.roadmap.domain.node.presentation.data.request.UpdateNodeReqDto;
import com.cowork.roadmap.domain.node.presentation.data.response.NodeResDto;
import com.cowork.roadmap.domain.node.repository.RoadmapNodeRepository;
import com.cowork.roadmap.domain.node.service.ModifyRoadmapNodeService;
import com.cowork.roadmap.domain.node.service.support.RoadmapNodeLookupSupport;
import com.cowork.roadmap.domain.roadmap.service.RoadmapAccessGuard;
import com.cowork.roadmap.domain.roadmap.service.support.RoadmapLookupSupport;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class ModifyRoadmapNodeServiceImpl implements ModifyRoadmapNodeService {

    private final RoadmapNodeRepository nodeRepository;
    private final RoadmapAccessGuard accessGuard;
    private final RoadmapLookupSupport roadmapLookupSupport;
    private final RoadmapNodeLookupSupport nodeLookupSupport;

    /**
     * 저장이 position까지 전체 행을 덮어쓰므로 재정렬과 같은 로드맵 잠금 아래에서 노드를 다시 읽어 수정한다. 잠금 전에 노드를 읽으므로
     * READ COMMITTED로 실행해 잠금 이후 조회가 최신 커밋을 보게 한다.
     */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Mono<NodeResDto> execute(Long userId, String userRole, Long nodeId, UpdateNodeReqDto request) {
        return nodeLookupSupport.findNodeOrThrow(nodeId)
                .flatMap(found -> roadmapLookupSupport.findRoadmapForUpdateOrThrow(found.getRoadmapId())
                        .flatMap(roadmap -> accessGuard.requireMutable(roadmap, userId, userRole)
                                .then(nodeLookupSupport.findNodeOrThrow(nodeId))
                                .flatMap(node -> {
                                    RoadmapNode updated = node.toBuilder()
                                            .title(request.title() != null ? request.title() : node.getTitle())
                                            .content(request.content() != null ? request.content() : node.getContent())
                                            .sourceUrl(request.sourceUrl() != null
                                                    ? request.sourceUrl()
                                                    : node.getSourceUrl())
                                            .sourceTitle(request.sourceTitle() != null
                                                    ? request.sourceTitle()
                                                    : node.getSourceTitle())
                                            .build();
                                    updated.copyAuditFrom(node);
                                    updated.setLastModifiedBy(userId);
                                    return nodeRepository.save(updated);
                                })
                                .flatMap(saved -> nodeLookupSupport.loadReferences(saved.getId())
                                        .map(refs -> NodeResDto.of(saved, refs)))));
    }
}
