package com.cowork.roadmap.domain.roadmap.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.cowork.roadmap.domain.roadmap.entity.Roadmap;
import com.cowork.roadmap.domain.roadmap.entity.RoadmapScope;
import com.cowork.roadmap.domain.roadmap.presentation.data.response.RoadmapResDto;
import com.cowork.roadmap.domain.roadmap.repository.RoadmapRepository;
import com.cowork.roadmap.domain.roadmap.service.impl.ListRoadmapsServiceImpl;

import team.themoment.sdk.exception.ExpectedException;

class ListRoadmapsServiceTest {

    private final RoadmapRepository roadmapRepository = mock(RoadmapRepository.class);
    private final RoadmapAccessGuard accessGuard = mock(RoadmapAccessGuard.class);
    private final ListRoadmapsServiceImpl listRoadmapsService = new ListRoadmapsServiceImpl(roadmapRepository,
            accessGuard);

    @Test
    void listByProject_returnsOnlyReadableRoadmaps() {
        Roadmap readable = projectRoadmap(1L, 5L);
        Roadmap foreign = projectRoadmap(2L, 6L);
        when(roadmapRepository.findListByOwnerProjectId(100L, null)).thenReturn(List.of(foreign, readable));
        when(accessGuard.canReadTeam(5L, 1L, "MEMBER")).thenReturn(true);
        when(accessGuard.canReadTeam(6L, 1L, "MEMBER")).thenReturn(false);

        List<RoadmapResDto> result = listRoadmapsService.execute(1L, "MEMBER", null, null, null, 100L);

        assertEquals(1, result.size());
        assertEquals(1L, result.get(0).id());
    }

    @Test
    void listByTeam_withCategory_filtersByCategory() {
        when(roadmapRepository.findListByOwnerTeamId(5L, "Flutter")).thenReturn(List.of(teamRoadmap(1L, "Flutter")));

        List<RoadmapResDto> result = listRoadmapsService.execute(1L, "MEMBER", null, "Flutter", 5L, null);

        assertEquals(1, result.size());
        assertEquals(1L, result.get(0).id());
        verify(accessGuard).requireTeamReadable(5L, 1L, "MEMBER");
    }

    @Test
    void listByTeam_withoutCategory_doesNotFilter() {
        when(roadmapRepository.findListByOwnerTeamId(5L, null))
                .thenReturn(List.of(teamRoadmap(2L, "Server"), teamRoadmap(1L, "Flutter")));

        List<RoadmapResDto> result = listRoadmapsService.execute(1L, "MEMBER", null, null, 5L, null);

        assertEquals(2, result.size());
    }

    @Test
    void listByTeam_withBlankCategory_doesNotFilter() {
        when(roadmapRepository.findListByOwnerTeamId(5L, null))
                .thenReturn(List.of(teamRoadmap(2L, "Server"), teamRoadmap(1L, "Flutter")));

        List<RoadmapResDto> result = listRoadmapsService.execute(1L, "MEMBER", null, " ", 5L, null);

        assertEquals(2, result.size());
    }

    @Test
    void listByProject_withCategory_filtersByCategory() {
        when(roadmapRepository.findListByOwnerProjectId(100L, "Flutter")).thenReturn(List.of(projectRoadmap(1L, 5L)));
        when(accessGuard.canReadTeam(5L, 1L, "MEMBER")).thenReturn(true);

        List<RoadmapResDto> result = listRoadmapsService.execute(1L, "MEMBER", null, "Flutter", null, 100L);

        assertEquals(1, result.size());
        assertEquals(1L, result.get(0).id());
    }

    @Test
    void listGlobal_withBlankCategory_doesNotFilter() {
        when(roadmapRepository.findListByScope(RoadmapScope.GLOBAL.name(), null))
                .thenReturn(List.of(Roadmap.builder().id(1L).scope(RoadmapScope.GLOBAL.name()).build()));

        List<RoadmapResDto> result = listRoadmapsService.execute(1L, "MEMBER", null, "", null, null);

        assertEquals(1, result.size());
    }

    @Test
    void listByTeam_withMismatchedScope_throwsBadRequest() {
        assertThrows(ExpectedException.class,
                () -> listRoadmapsService.execute(1L, "MEMBER", RoadmapScope.GLOBAL, null, 5L, null));

        verifyNoInteractions(roadmapRepository);
    }

    @Test
    void listByProject_withMismatchedScope_throwsBadRequest() {
        assertThrows(ExpectedException.class,
                () -> listRoadmapsService.execute(1L, "MEMBER", RoadmapScope.TEAM, null, null, 100L));

        verifyNoInteractions(roadmapRepository);
    }

    @Test
    void listByTeam_withMatchingScope_returnsRoadmaps() {
        when(roadmapRepository.findListByOwnerTeamId(any(), any())).thenReturn(List.of(teamRoadmap(1L, "Flutter")));

        List<RoadmapResDto> result = listRoadmapsService.execute(1L, "MEMBER", RoadmapScope.TEAM, null, 5L, null);

        assertEquals(1, result.size());
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
