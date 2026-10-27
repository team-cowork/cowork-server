package com.cowork.roadmap.domain.assignment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.cowork.roadmap.domain.assignment.entity.RoadmapAssignment;
import com.cowork.roadmap.domain.assignment.repository.RoadmapAssignmentRepository;
import com.cowork.roadmap.domain.assignment.service.impl.ListRoadmapAssignmentsServiceImpl;
import com.cowork.roadmap.domain.roadmap.entity.Roadmap;
import com.cowork.roadmap.domain.roadmap.repository.RoadmapRepository;
import com.cowork.roadmap.domain.roadmap.service.RoadmapAccessGuard;
import com.cowork.roadmap.domain.roadmap.service.support.RoadmapLookupSupport;
import com.cowork.roadmap.global.team.TeamMemberProjectionReadiness;
import com.cowork.roadmap.global.team.TeamMemberProjectionRepository;

import team.themoment.sdk.exception.ExpectedException;

class ListRoadmapAssignmentsServiceTest {
    private final RoadmapRepository roadmaps = mock(RoadmapRepository.class);
    private final RoadmapAssignmentRepository assignments = mock(RoadmapAssignmentRepository.class);
    private final TeamMemberProjectionRepository memberships = mock(TeamMemberProjectionRepository.class);
    private final TeamMemberProjectionReadiness readiness = mock(TeamMemberProjectionReadiness.class);
    private final ListRoadmapAssignmentsServiceImpl service = new ListRoadmapAssignmentsServiceImpl(assignments,
            new RoadmapAccessGuard(memberships, readiness),
            new RoadmapLookupSupport(roadmaps));

    ListRoadmapAssignmentsServiceTest() {
        when(roadmaps.findById(1L)).thenReturn(Optional.of(Roadmap.builder().id(1L).scope("GLOBAL").build()));
        when(assignments.findByRoadmapIdOrderByIdDesc(1L))
                .thenReturn(List.of(assignment(1L, 10L), assignment(2L, 20L)));
        when(readiness.isReady()).thenReturn(true);
    }

    @Test
    void globalReadPermission_doesNotExposeOtherTeamsAssignments() {
        when(memberships.findActiveRole(10L, 7L)).thenReturn(Optional.of("MEMBER"));
        when(memberships.findActiveRole(20L, 7L)).thenReturn(Optional.empty());

        assertThat(service.execute(7L, "MEMBER", 1L)).singleElement()
                .matches(assignment -> assignment.teamId().equals(10L));
    }

    @Test
    void platformAdmin_canInspectLegacyAssignments() {
        assertThat(service.execute(7L, "ADMIN", 1L)).hasSize(2);
    }

    @Test
    void unavailableMembership_doesNotGrantAccess() {
        when(readiness.isReady()).thenReturn(false);
        ExpectedException error = assertThrows(ExpectedException.class, () -> service.execute(7L, "MEMBER", 1L));
        assertTrue(error.getStatusCode() == HttpStatus.SERVICE_UNAVAILABLE);
    }

    private RoadmapAssignment assignment(Long id, Long teamId) {
        return RoadmapAssignment.builder()
                .id(id)
                .roadmapId(1L)
                .teamId(teamId)
                .scope("TEAM")
                .assigneeUserId(7L)
                .assignedBy(8L)
                .status("ASSIGNED")
                .build();
    }
}
