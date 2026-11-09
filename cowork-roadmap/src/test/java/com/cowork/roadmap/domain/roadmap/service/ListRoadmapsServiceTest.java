package com.cowork.roadmap.domain.roadmap.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.cowork.roadmap.domain.roadmap.entity.Roadmap;
import com.cowork.roadmap.domain.roadmap.entity.RoadmapScope;
import com.cowork.roadmap.domain.roadmap.presentation.data.response.RoadmapResDto;
import com.cowork.roadmap.domain.roadmap.repository.RoadmapRepository;
import com.cowork.roadmap.domain.roadmap.service.impl.ListRoadmapsServiceImpl;

class ListRoadmapsServiceTest {

    private final RoadmapRepository roadmapRepository = mock(RoadmapRepository.class);
    private final RoadmapAccessGuard accessGuard = mock(RoadmapAccessGuard.class);
    private final ListRoadmapsServiceImpl listRoadmapsService = new ListRoadmapsServiceImpl(roadmapRepository,
            accessGuard);

    @Test
    void listByProject_returnsOnlyReadableRoadmaps() {
        Roadmap readable = projectRoadmap(1L, 5L);
        Roadmap foreign = projectRoadmap(2L, 6L);
        when(roadmapRepository.findByOwnerProjectIdOrderByIdDesc(100L)).thenReturn(List.of(foreign, readable));
        when(accessGuard.canReadTeam(5L, 1L, "MEMBER")).thenReturn(true);
        when(accessGuard.canReadTeam(6L, 1L, "MEMBER")).thenReturn(false);

        List<RoadmapResDto> result = listRoadmapsService.execute(1L, "MEMBER", null, null, null, 100L);

        assertEquals(1, result.size());
        assertEquals(1L, result.get(0).id());
    }

    @Test
    void listByTeam_withCategory_queriesByTeamAndCategory() {
        Roadmap flutter = teamRoadmap(1L, "Flutter");
        when(roadmapRepository.findByOwnerTeamIdAndCategoryOrderByIdDesc(5L, "Flutter")).thenReturn(List.of(flutter));

        List<RoadmapResDto> result = listRoadmapsService.execute(1L, "MEMBER", null, "Flutter", 5L, null);

        assertEquals(1, result.size());
        assertEquals(1L, result.get(0).id());
        verify(accessGuard).requireTeamReadable(5L, 1L, "MEMBER");
        verify(roadmapRepository, never()).findByOwnerTeamIdOrderByIdDesc(anyLong());
    }

    @Test
    void listByProject_withCategory_queriesByProjectAndCategory() {
        Roadmap flutter = projectRoadmap(1L, 5L);
        when(roadmapRepository.findByOwnerProjectIdAndCategoryOrderByIdDesc(100L, "Flutter"))
                .thenReturn(List.of(flutter));
        when(accessGuard.canReadTeam(5L, 1L, "MEMBER")).thenReturn(true);

        List<RoadmapResDto> result = listRoadmapsService.execute(1L, "MEMBER", null, "Flutter", null, 100L);

        assertEquals(1, result.size());
        assertEquals(1L, result.get(0).id());
        verify(roadmapRepository, never()).findByOwnerProjectIdOrderByIdDesc(anyLong());
    }

    private static Roadmap teamRoadmap(Long id, String category) {
        return Roadmap.builder().id(id).scope(RoadmapScope.TEAM.name()).ownerTeamId(5L).category(category).build();
    }

    private static Roadmap projectRoadmap(Long id, Long teamId) {
        return Roadmap.builder()
                .id(id)
                .scope(RoadmapScope.PROJECT.name())
                .ownerTeamId(teamId)
                .ownerProjectId(100L)
                .build();
    }
}
