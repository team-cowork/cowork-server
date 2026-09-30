package com.cowork.roadmap.domain.node.repository;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;

import com.cowork.roadmap.domain.node.entity.RoadmapNode;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface RoadmapNodeRepository extends R2dbcRepository<RoadmapNode, Long> {

    Flux<RoadmapNode> findByRoadmapIdOrderByPositionAsc(Long roadmapId);

    Mono<Long> countByRoadmapIdAndParentIdIsNull(Long roadmapId);

    Mono<Long> countByRoadmapIdAndParentId(Long roadmapId, Long parentId);

    /** 하위 노드·과제 생성이 삭제 중인 노드를 참조하지 않도록 노드 삭제의 배타 잠금과 직렬화한다. */
    @Query("SELECT * FROM tb_roadmap_nodes WHERE id = :id FOR SHARE")
    Mono<RoadmapNode> findByIdForShare(Long id);

    /** 잠금 읽기로 transaction snapshot 이후 커밋된 하위 노드까지 포함해 서브트리를 계산한다. */
    @Query("SELECT * FROM tb_roadmap_nodes WHERE roadmap_id = :roadmapId FOR UPDATE")
    Flux<RoadmapNode> findAllByRoadmapIdForUpdate(Long roadmapId);
}
