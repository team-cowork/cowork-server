package com.cowork.roadmap.domain.roadmap.repository;

import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;

import com.cowork.roadmap.domain.roadmap.entity.Roadmap;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface RoadmapRepository extends R2dbcRepository<Roadmap, Long> {

    Flux<Roadmap> findByScopeOrderByIdDesc(String scope);

    Flux<Roadmap> findByScopeAndCategoryOrderByIdDesc(String scope, String category);

    Flux<Roadmap> findByOwnerTeamIdOrderByIdDesc(Long ownerTeamId);

    Flux<Roadmap> findByOwnerProjectIdOrderByIdDesc(Long ownerProjectId);

    /**
     * 노드 생성·수정·재정렬·삭제, 참고 자료와 과제 생성을 로드맵 단위로 직렬화한다. 모든 경로가 로드맵 행을 노드 행보다 먼저 잠가 잠금
     * 순서를 하나로 유지한다.
     */
    @Query("SELECT * FROM tb_roadmaps WHERE id = :id FOR UPDATE")
    Mono<Roadmap> findByIdForUpdate(Long id);
}
