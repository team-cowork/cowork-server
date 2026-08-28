package com.cowork.roadmap.domain.roadmap.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.cowork.roadmap.domain.roadmap.entity.Roadmap;
import com.cowork.roadmap.domain.roadmap.entity.RoadmapScope;
import com.cowork.roadmap.domain.roadmap.repository.RoadmapRepository;
import com.cowork.roadmap.domain.roadmap.service.impl.ListRoadmapsServiceImpl;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import team.themoment.sdk.exception.ExpectedException;

class ListRoadmapsServiceTest {

    private final RoadmapRepository roadmapRepository = mock(RoadmapRepository.class);
    private final RoadmapAccessGuard accessGuard = mock(RoadmapAccessGuard.class);

    private final ListRoadmapsServiceImpl listRoadmapsService = new ListRoadmapsServiceImpl(roadmapRepository,
            accessGuard);

    @Test
    void list_byProjectId_queriesByProject() {
        Roadmap roadmap = customRoadmap(1L, 5L, 7L);
        when(roadmapRepository.findByOwnerProjectIdOrderByIdDesc(7L)).thenReturn(Flux.just(roadmap));
        when(accessGuard.requireReadable(roadmap, 1L, "MEMBER")).thenReturn(Mono.empty());

        StepVerifier.create(listRoadmapsService.execute(1L, "MEMBER", RoadmapScope.PROJECT, "cat", null, 7L))
                .expectNextCount(1)
                .verifyComplete();

        verify(roadmapRepository).findByOwnerProjectIdOrderByIdDesc(7L);
        verify(roadmapRepository, never()).findByOwnerTeamIdOrderByIdDesc(anyLong());
        verify(accessGuard).requireReadable(roadmap, 1L, "MEMBER");
    }

    @Test
    void list_byTeamId_queriesByTeam() {
        when(roadmapRepository.findByOwnerTeamIdOrderByIdDesc(5L)).thenReturn(Flux.just(roadmap(1L)));
        when(accessGuard.requireTeamReadable(5L, 1L, "MEMBER")).thenReturn(Mono.empty());

        StepVerifier.create(listRoadmapsService.execute(1L, "MEMBER", RoadmapScope.TEAM, "cat", 5L, null))
                .expectNextCount(1)
                .verifyComplete();

        verify(roadmapRepository).findByOwnerTeamIdOrderByIdDesc(5L);
        verify(accessGuard).requireTeamReadable(5L, 1L, "MEMBER");
    }

    @Test
    void list_byScopeAndCategory_queriesByScopeAndCategory() {
        when(roadmapRepository.findByScopeAndCategoryOrderByIdDesc(RoadmapScope.GLOBAL.name(), "Flutter"))
                .thenReturn(Flux.just(roadmap(1L)));

        StepVerifier.create(listRoadmapsService.execute(1L, "MEMBER", RoadmapScope.GLOBAL, "Flutter", null, null))
                .expectNextCount(1)
                .verifyComplete();

        verify(roadmapRepository).findByScopeAndCategoryOrderByIdDesc(RoadmapScope.GLOBAL.name(), "Flutter");
    }

    @Test
    void list_withNullScopeAndCategory_defaultsToGlobalScopeOnly() {
        when(roadmapRepository.findByScopeOrderByIdDesc(RoadmapScope.GLOBAL.name())).thenReturn(Flux.just(roadmap(1L)));

        StepVerifier.create(listRoadmapsService.execute(1L, "MEMBER", null, null, null, null))
                .expectNextCount(1)
                .verifyComplete();

        verify(roadmapRepository).findByScopeOrderByIdDesc(RoadmapScope.GLOBAL.name());
    }

    @Test
    void list_mapsToResponseDto() {
        when(roadmapRepository.findByScopeOrderByIdDesc(RoadmapScope.GLOBAL.name()))
                .thenReturn(Flux.just(roadmap(42L)));

        StepVerifier.create(listRoadmapsService.execute(1L, "MEMBER", null, null, null, null))
                .assertNext(response -> assertThat(response.id()).isEqualTo(42L))
                .verifyComplete();
    }

    @Test
    void list_customScopeWithoutOwnerFilter_isRejected() {
        StepVerifier.create(listRoadmapsService.execute(1L, "MEMBER", RoadmapScope.TEAM, null, null, null))
                .expectErrorMatches(error -> error instanceof ExpectedException expected
                        && expected.getStatusCode() == HttpStatus.BAD_REQUEST)
                .verify();
    }

    private static Roadmap roadmap(Long id) {
        return Roadmap.builder().id(id).scope(RoadmapScope.GLOBAL.name()).build();
    }

    private static Roadmap customRoadmap(Long id, Long ownerTeamId, Long ownerProjectId) {
        return Roadmap.builder()
                .id(id)
                .scope(RoadmapScope.PROJECT.name())
                .ownerTeamId(ownerTeamId)
                .ownerProjectId(ownerProjectId)
                .build();
    }
}
