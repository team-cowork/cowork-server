package com.cowork.roadmap.domain.roadmap.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.cowork.roadmap.domain.roadmap.entity.Roadmap;
import com.cowork.roadmap.domain.roadmap.entity.RoadmapScope;
import com.cowork.roadmap.global.team.TeamMemberProjectionReadiness;
import com.cowork.roadmap.global.team.TeamMemberProjectionRepository;

import team.themoment.sdk.exception.ExpectedException;

class RoadmapAccessGuardTest {

    private final TeamMemberProjectionRepository teamMemberships = mock(TeamMemberProjectionRepository.class);
    private final TeamMemberProjectionReadiness projectionReadiness = mock(TeamMemberProjectionReadiness.class);
    private final RoadmapAccessGuard accessGuard = new RoadmapAccessGuard(teamMemberships, projectionReadiness);

    RoadmapAccessGuardTest() {
        when(projectionReadiness.isReady()).thenReturn(true);
    }

    @Nested
    class RequireMutable {

        @Test
        void globalScopeByNonAdmin_isForbidden() {
            Roadmap roadmap = roadmap(RoadmapScope.GLOBAL, 1L, null);

            ExpectedException error = assertThrows(ExpectedException.class,
                    () -> accessGuard.requireMutable(roadmap, 1L, "MEMBER"));
            assertTrue(error.getStatusCode() == HttpStatus.FORBIDDEN);
            verifyNoInteractions(teamMemberships);
        }

        @Test
        void globalScopeByAdmin_succeeds() {
            Roadmap roadmap = roadmap(RoadmapScope.GLOBAL, 1L, null);

            accessGuard.requireMutable(roadmap, 1L, "ADMIN");
            verifyNoInteractions(teamMemberships);
        }

        @Test
        void customScopeByActiveCreator_succeeds() {
            Roadmap roadmap = roadmap(RoadmapScope.TEAM, 7L, 99L);
            when(teamMemberships.findActiveRole(99L, 7L)).thenReturn(Optional.of("MEMBER"));

            accessGuard.requireMutable(roadmap, 7L, "MEMBER");
        }

        @Test
        void customScopeByLeftCreator_isForbidden() {
            Roadmap roadmap = roadmap(RoadmapScope.TEAM, 7L, 99L);
            when(teamMemberships.findActiveRole(99L, 7L)).thenReturn(Optional.empty());

            ExpectedException error = assertThrows(ExpectedException.class,
                    () -> accessGuard.requireMutable(roadmap, 7L, "MEMBER"));
            assertTrue(error.getStatusCode() == HttpStatus.FORBIDDEN);
        }

        @Test
        void customScopeByTeamAdmin_succeeds() {
            Roadmap roadmap = roadmap(RoadmapScope.TEAM, 7L, 99L);
            when(teamMemberships.findActiveRole(99L, 8L)).thenReturn(Optional.of("ADMIN"));

            accessGuard.requireMutable(roadmap, 8L, "MEMBER");
        }

        @Test
        void customScopeByCreator_whenProjectionNotReady_isUnavailable() {
            Roadmap roadmap = roadmap(RoadmapScope.TEAM, 7L, 99L);
            when(projectionReadiness.isReady()).thenReturn(false);

            ExpectedException error = assertThrows(ExpectedException.class,
                    () -> accessGuard.requireMutable(roadmap, 7L, "MEMBER"));
            assertTrue(error.getStatusCode() == HttpStatus.SERVICE_UNAVAILABLE);
            verifyNoInteractions(teamMemberships);
        }

        @Test
        void customScopeByTeamMember_isForbidden() {
            Roadmap roadmap = roadmap(RoadmapScope.TEAM, 7L, 99L);
            when(teamMemberships.findActiveRole(99L, 8L)).thenReturn(Optional.of("MEMBER"));

            ExpectedException error = assertThrows(ExpectedException.class,
                    () -> accessGuard.requireMutable(roadmap, 8L, "MEMBER"));
            assertTrue(error.getStatusCode() == HttpStatus.FORBIDDEN);
        }
    }

    @Nested
    class RequireTeamMember {

        @Test
        void activeMember_succeeds() {
            when(teamMemberships.findActiveRole(99L, 7L)).thenReturn(Optional.of("MEMBER"));

            accessGuard.requireTeamMember(99L, 7L);
        }

        @Test
        void leftMember_isForbidden() {
            when(teamMemberships.findActiveRole(99L, 7L)).thenReturn(Optional.empty());

            ExpectedException error = assertThrows(ExpectedException.class,
                    () -> accessGuard.requireTeamMember(99L, 7L));
            assertTrue(error.getStatusCode() == HttpStatus.FORBIDDEN);
        }

        @Test
        void projectionNotReady_isUnavailable() {
            when(projectionReadiness.isReady()).thenReturn(false);

            ExpectedException error = assertThrows(ExpectedException.class,
                    () -> accessGuard.requireTeamMember(99L, 7L));
            assertTrue(error.getStatusCode() == HttpStatus.SERVICE_UNAVAILABLE);
            verifyNoInteractions(teamMemberships);
        }
    }

    @Nested
    class RequireCreatable {

        @Test
        void teamScopeByOwner_succeeds() {
            when(teamMemberships.findActiveRole(99L, 7L)).thenReturn(Optional.of("OWNER"));

            accessGuard.requireCreatable(7L, "MEMBER", RoadmapScope.TEAM, 99L);
        }
    }

    @Nested
    class RequireReadable {

        @Test
        void globalScope_succeeds() {
            Roadmap roadmap = roadmap(RoadmapScope.GLOBAL, 1L, null);

            accessGuard.requireReadable(roadmap, 123L, "MEMBER");
        }

        @Test
        void customScopeByTeamMember_succeeds() {
            Roadmap roadmap = roadmap(RoadmapScope.TEAM, 1L, 99L);
            when(teamMemberships.findActiveRole(99L, 123L)).thenReturn(Optional.of("MEMBER"));

            accessGuard.requireReadable(roadmap, 123L, "MEMBER");
        }

        @Test
        void customScopeByOutsider_isForbidden() {
            Roadmap roadmap = roadmap(RoadmapScope.TEAM, 1L, 99L);
            when(teamMemberships.findActiveRole(99L, 123L)).thenReturn(Optional.empty());

            ExpectedException error = assertThrows(ExpectedException.class,
                    () -> accessGuard.requireReadable(roadmap, 123L, "MEMBER"));
            assertTrue(error.getStatusCode() == HttpStatus.FORBIDDEN);
        }
    }

    private static Roadmap roadmap(RoadmapScope scope, Long createdBy, Long ownerTeamId) {
        Roadmap roadmap = Roadmap.builder().id(1L).scope(scope.name()).ownerTeamId(ownerTeamId).build();
        roadmap.setCreatedBy(createdBy);
        return roadmap;
    }
}
