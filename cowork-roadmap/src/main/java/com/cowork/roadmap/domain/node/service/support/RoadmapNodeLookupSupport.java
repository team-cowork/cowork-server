package com.cowork.roadmap.domain.node.service.support;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.cowork.roadmap.domain.node.entity.RoadmapNode;
import com.cowork.roadmap.domain.node.entity.RoadmapNodeReference;
import com.cowork.roadmap.domain.node.presentation.data.response.NodeReferenceResDto;
import com.cowork.roadmap.domain.node.repository.RoadmapNodeReferenceRepository;
import com.cowork.roadmap.domain.node.repository.RoadmapNodeRepository;

import reactor.core.publisher.Mono;
import team.themoment.sdk.exception.ExpectedException;

@Component
public class RoadmapNodeLookupSupport {

    private final RoadmapNodeRepository nodeRepository;
    private final RoadmapNodeReferenceRepository referenceRepository;

    public RoadmapNodeLookupSupport(RoadmapNodeRepository nodeRepository,
            RoadmapNodeReferenceRepository referenceRepository) {
        this.nodeRepository = nodeRepository;
        this.referenceRepository = referenceRepository;
    }

    public Mono<RoadmapNode> findNodeOrThrow(Long nodeId) {
        return nodeRepository.findById(nodeId)
                .switchIfEmpty(Mono.error(new ExpectedException("노드를 찾을 수 없습니다.", HttpStatus.NOT_FOUND)));
    }

    public Mono<RoadmapNodeReference> findReferenceOrThrow(Long referenceId) {
        return referenceRepository.findById(referenceId)
                .switchIfEmpty(Mono.error(new ExpectedException("관련 자료를 찾을 수 없습니다.", HttpStatus.NOT_FOUND)));
    }

    public Mono<List<NodeReferenceResDto>> loadReferences(Long nodeId) {
        return referenceRepository.findByNodeIdOrderByPositionAsc(nodeId).map(NodeReferenceResDto::from).collectList();
    }
}
