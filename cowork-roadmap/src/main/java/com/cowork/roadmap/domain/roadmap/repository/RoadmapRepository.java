package com.cowork.roadmap.domain.roadmap.repository;

import org.springframework.data.r2dbc.repository.R2dbcRepository;

import com.cowork.roadmap.domain.roadmap.entity.Roadmap;

import reactor.core.publisher.Flux;

public interface RoadmapRepository extends R2dbcRepository<Roadmap, Long> {

    Flux<Roadmap> findByScopeOrderByIdDesc(String scope);

    Flux<Roadmap> findByScopeAndCategoryOrderByIdDesc(String scope, String category);

    Flux<Roadmap> findByOwnerTeamIdOrderByIdDesc(Long ownerTeamId);

    Flux<Roadmap> findByOwnerProjectIdOrderByIdDesc(Long ownerProjectId);
}
