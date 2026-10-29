package com.cowork.roadmap.domain.roadmap.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import com.cowork.roadmap.domain.roadmap.entity.Roadmap;
import com.cowork.roadmap.domain.roadmap.entity.RoadmapScope;
import com.cowork.roadmap.domain.roadmap.presentation.data.request.UpdateRoadmapReqDto;
import com.cowork.roadmap.domain.roadmap.repository.RoadmapRepository;
import com.cowork.roadmap.domain.roadmap.service.impl.ModifyRoadmapServiceImpl;
import com.cowork.roadmap.domain.roadmap.service.support.RoadmapLookupSupport;

import team.themoment.sdk.exception.ExpectedException;

class ModifyRoadmapServiceTest {

    private final RoadmapRepository roadmapRepository = mock(RoadmapRepository.class);
    private final RoadmapAccessGuard accessGuard = mock(RoadmapAccessGuard.class);

    private final RoadmapLookupSupport lookupSupport = new RoadmapLookupSupport(roadmapRepository);
    private final ModifyRoadmapServiceImpl modifyRoadmapService = new ModifyRoadmapServiceImpl(roadmapRepository,
            accessGuard,
            lookupSupport);

    @Test
    void modifyRoadmap_updatesOnlyNonNullFields() {
        Roadmap roadmap = roadmap(10L, "원제목", "원설명", "원카테고리");
        when(roadmapRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(roadmap));

        ArgumentCaptor<Roadmap> savedCaptor = ArgumentCaptor.forClass(Roadmap.class);
        when(roadmapRepository.saveAndFlush(savedCaptor.capture())).thenAnswer(invocation -> invocation.getArgument(0));

        UpdateRoadmapReqDto request = new UpdateRoadmapReqDto("새제목", null, null);

        var response = modifyRoadmapService.execute(7L, "ADMIN", 10L, request);
        assertThat(response.title()).isEqualTo("새제목");
        assertThat(response.description()).isEqualTo("원설명");
        assertThat(response.category()).isEqualTo("원카테고리");

        assertThat(savedCaptor.getValue().getLastModifiedBy()).isEqualTo(7L);
        verify(roadmapRepository, never()).findById(any());
    }

    @Test
    void modifyRoadmap_withoutFieldChanges_refreshesModifiedTimeAndKeepsCreationAudit() {
        Roadmap roadmap = roadmap(10L, "원제목", "원설명", "원카테고리");
        LocalDateTime createdAt = LocalDateTime.of(2025, 1, 1, 0, 0);
        LocalDateTime updatedAt = createdAt.plusDays(1);
        roadmap.setCreatedAt(createdAt);
        roadmap.setUpdatedAt(updatedAt);
        roadmap.setCreatedBy(3L);
        roadmap.setLastModifiedBy(7L);
        when(roadmapRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(roadmap));
        ArgumentCaptor<Roadmap> savedCaptor = ArgumentCaptor.forClass(Roadmap.class);
        when(roadmapRepository.saveAndFlush(savedCaptor.capture())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = modifyRoadmapService.execute(7L, "ADMIN", 10L, new UpdateRoadmapReqDto(null, null, null));

        Roadmap saved = savedCaptor.getValue();
        assertThat(saved.getTitle()).isEqualTo(roadmap.getTitle());
        assertThat(saved.getDescription()).isEqualTo(roadmap.getDescription());
        assertThat(saved.getCategory()).isEqualTo(roadmap.getCategory());
        assertThat(saved.getCreatedAt()).isEqualTo(createdAt);
        assertThat(saved.getCreatedBy()).isEqualTo(3L);
        assertThat(saved.getLastModifiedBy()).isEqualTo(7L);
        assertThat(saved.getUpdatedAt()).isAfter(updatedAt);
        assertThat(response.createdAt()).isEqualTo(createdAt);
        assertThat(response.updatedAt()).isEqualTo(saved.getUpdatedAt());
    }

    @Test
    void modifyRoadmap_roadmapNotFound_failsWithNotFound() {
        when(roadmapRepository.findByIdForUpdate(99L)).thenReturn(Optional.empty());

        UpdateRoadmapReqDto request = new UpdateRoadmapReqDto("새제목", null, null);

        ExpectedException error = assertThrows(ExpectedException.class,
                () -> modifyRoadmapService.execute(1L, "ADMIN", 99L, request));
        assertTrue(error.getStatusCode() == HttpStatus.NOT_FOUND);
    }

    private static Roadmap roadmap(Long id, String title, String description, String category) {
        return Roadmap.builder()
                .id(id)
                .scope(RoadmapScope.GLOBAL.name())
                .title(title)
                .description(description)
                .category(category)
                .build();
    }
}
