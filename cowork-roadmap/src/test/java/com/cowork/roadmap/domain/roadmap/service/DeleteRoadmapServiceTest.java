package com.cowork.roadmap.domain.roadmap.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.cowork.roadmap.domain.roadmap.entity.Roadmap;
import com.cowork.roadmap.domain.roadmap.entity.RoadmapScope;
import com.cowork.roadmap.domain.roadmap.repository.RoadmapRepository;
import com.cowork.roadmap.domain.roadmap.service.impl.DeleteRoadmapServiceImpl;
import com.cowork.roadmap.domain.roadmap.service.support.RoadmapLookupSupport;

import team.themoment.sdk.exception.ExpectedException;

class DeleteRoadmapServiceTest {

    private final RoadmapRepository roadmapRepository = mock(RoadmapRepository.class);
    private final RoadmapAccessGuard accessGuard = mock(RoadmapAccessGuard.class);

    private final RoadmapLookupSupport lookupSupport = new RoadmapLookupSupport(roadmapRepository);
    private final DeleteRoadmapServiceImpl deleteRoadmapService = new DeleteRoadmapServiceImpl(roadmapRepository,
            accessGuard,
            lookupSupport);

    @Test
    void deleteRoadmap_whenMutable_deletes() {
        Roadmap roadmap = roadmap(10L);
        when(roadmapRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(roadmap));

        deleteRoadmapService.execute(1L, "ADMIN", 10L);

        verify(roadmapRepository).delete(roadmap);
    }

    @Test
    void deleteRoadmap_roadmapNotFound_failsWithNotFound() {
        when(roadmapRepository.findByIdForUpdate(99L)).thenReturn(Optional.empty());

        ExpectedException error = assertThrows(ExpectedException.class,
                () -> deleteRoadmapService.execute(1L, "ADMIN", 99L));
        assertTrue(error.getStatusCode() == HttpStatus.NOT_FOUND);

        verify(roadmapRepository, never()).delete(any());
    }

    @Test
    void deleteRoadmap_whenNotMutable_doesNotDelete() {
        Roadmap roadmap = roadmap(10L);
        when(roadmapRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(roadmap));
        doThrow(new ExpectedException("권한 없음", HttpStatus.FORBIDDEN)).when(accessGuard)
                .requireMutable(any(), anyLong(), anyString());

        ExpectedException error = assertThrows(ExpectedException.class,
                () -> deleteRoadmapService.execute(2L, "MEMBER", 10L));
        assertTrue(error.getStatusCode() == HttpStatus.FORBIDDEN);

        verify(roadmapRepository, never()).delete(any());
    }

    @Test
    void customRoadmap_keepsExistingDeletionPolicy() {
        Roadmap roadmap = Roadmap.builder().id(10L).scope(RoadmapScope.TEAM.name()).ownerTeamId(5L).build();
        when(roadmapRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(roadmap));

        deleteRoadmapService.execute(1L, "MEMBER", 10L);
        verify(roadmapRepository).delete(roadmap);
    }

    private static Roadmap roadmap(Long id) {
        return Roadmap.builder().id(id).scope(RoadmapScope.GLOBAL.name()).build();
    }
}
