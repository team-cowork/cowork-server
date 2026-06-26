package com.cowork.roadmap.domain.roadmap.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.cowork.roadmap.domain.roadmap.entity.Roadmap;
import com.cowork.roadmap.domain.roadmap.entity.RoadmapScope;
import com.cowork.roadmap.domain.roadmap.presentation.data.request.UpdateRoadmapReqDto;
import com.cowork.roadmap.domain.roadmap.repository.RoadmapRepository;
import com.cowork.roadmap.domain.roadmap.service.impl.ModifyRoadmapServiceImpl;
import com.cowork.roadmap.domain.roadmap.service.support.RoadmapLookupSupport;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import team.themoment.sdk.exception.ExpectedException;

class ModifyRoadmapServiceImplTest {

    private final RoadmapRepository roadmapRepository = mock(RoadmapRepository.class);
    private final RoadmapAccessGuard accessGuard = mock(RoadmapAccessGuard.class);

    private final RoadmapLookupSupport lookupSupport = new RoadmapLookupSupport(roadmapRepository);
    private final ModifyRoadmapServiceImpl modifyRoadmapService = new ModifyRoadmapServiceImpl(roadmapRepository,
            accessGuard,
            lookupSupport);

    @Test
    void modifyRoadmap_updatesOnlyNonNullFields() {
        Roadmap roadmap = roadmap(10L, "원제목", "원설명", "원카테고리");
        when(roadmapRepository.findById(10L)).thenReturn(Mono.just(roadmap));
        when(accessGuard.requireMutable(any(), anyLong(), anyString())).thenReturn(Mono.empty());
        when(roadmapRepository.save(any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        UpdateRoadmapReqDto request = new UpdateRoadmapReqDto("새제목", null, null);

        StepVerifier.create(modifyRoadmapService.execute(7L, "ADMIN", 10L, request)).assertNext(response -> {
            assertThat(response.title()).isEqualTo("새제목");
            assertThat(response.description()).isEqualTo("원설명");
            assertThat(response.category()).isEqualTo("원카테고리");
        }).verifyComplete();

        assertThat(roadmap.getLastModifiedBy()).isEqualTo(7L);
    }

    @Test
    void modifyRoadmap_roadmapNotFound_failsWithNotFound() {
        when(roadmapRepository.findById(99L)).thenReturn(Mono.empty());

        UpdateRoadmapReqDto request = new UpdateRoadmapReqDto("새제목", null, null);

        StepVerifier.create(modifyRoadmapService.execute(1L, "ADMIN", 99L, request))
                .expectErrorMatches(error -> error instanceof ExpectedException expected
                        && expected.getStatusCode() == HttpStatus.NOT_FOUND)
                .verify();
    }

    private static Roadmap roadmap(Long id, String title, String description, String category) {
        Roadmap roadmap = new Roadmap();
        roadmap.setId(id);
        roadmap.setScope(RoadmapScope.GLOBAL.name());
        roadmap.setTitle(title);
        roadmap.setDescription(description);
        roadmap.setCategory(category);
        return roadmap;
    }
}
