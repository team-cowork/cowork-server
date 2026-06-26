package com.cowork.roadmap.domain.roadmap.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.cowork.roadmap.domain.roadmap.entity.Roadmap;
import com.cowork.roadmap.domain.roadmap.entity.RoadmapScope;
import com.cowork.roadmap.domain.roadmap.repository.RoadmapRepository;
import com.cowork.roadmap.domain.roadmap.service.impl.ListRoadmapsServiceImpl;

import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class ListRoadmapsServiceImplTest {

    private final RoadmapRepository roadmapRepository = mock(RoadmapRepository.class);

    private final ListRoadmapsServiceImpl listRoadmapsService = new ListRoadmapsServiceImpl(roadmapRepository);

    @Test
    void list_byProjectId_queriesByProject() {
        when(roadmapRepository.findByOwnerProjectIdOrderByIdDesc(7L)).thenReturn(Flux.just(roadmap(1L)));

        StepVerifier.create(listRoadmapsService.execute(RoadmapScope.TEAM, "cat", 5L, 7L))
                .expectNextCount(1)
                .verifyComplete();

        verify(roadmapRepository).findByOwnerProjectIdOrderByIdDesc(7L);
        verify(roadmapRepository, never()).findByOwnerTeamIdOrderByIdDesc(anyLong());
    }

    @Test
    void list_byTeamId_queriesByTeam() {
        when(roadmapRepository.findByOwnerTeamIdOrderByIdDesc(5L)).thenReturn(Flux.just(roadmap(1L)));

        StepVerifier.create(listRoadmapsService.execute(RoadmapScope.TEAM, "cat", 5L, null))
                .expectNextCount(1)
                .verifyComplete();

        verify(roadmapRepository).findByOwnerTeamIdOrderByIdDesc(5L);
    }

    @Test
    void list_byScopeAndCategory_queriesByScopeAndCategory() {
        when(roadmapRepository.findByScopeAndCategoryOrderByIdDesc(RoadmapScope.GLOBAL.name(), "Flutter"))
                .thenReturn(Flux.just(roadmap(1L)));

        StepVerifier.create(listRoadmapsService.execute(RoadmapScope.GLOBAL, "Flutter", null, null))
                .expectNextCount(1)
                .verifyComplete();

        verify(roadmapRepository).findByScopeAndCategoryOrderByIdDesc(RoadmapScope.GLOBAL.name(), "Flutter");
    }

    @Test
    void list_withNullScopeAndCategory_defaultsToGlobalScopeOnly() {
        when(roadmapRepository.findByScopeOrderByIdDesc(RoadmapScope.GLOBAL.name())).thenReturn(Flux.just(roadmap(1L)));

        StepVerifier.create(listRoadmapsService.execute(null, null, null, null)).expectNextCount(1).verifyComplete();

        verify(roadmapRepository).findByScopeOrderByIdDesc(RoadmapScope.GLOBAL.name());
    }

    @Test
    void list_mapsToResponseDto() {
        when(roadmapRepository.findByScopeOrderByIdDesc(RoadmapScope.GLOBAL.name()))
                .thenReturn(Flux.just(roadmap(42L)));

        StepVerifier.create(listRoadmapsService.execute(null, null, null, null))
                .assertNext(response -> assertThat(response.id()).isEqualTo(42L))
                .verifyComplete();
    }

    private static Roadmap roadmap(Long id) {
        Roadmap roadmap = new Roadmap();
        roadmap.setId(id);
        roadmap.setScope(RoadmapScope.GLOBAL.name());
        return roadmap;
    }
}
