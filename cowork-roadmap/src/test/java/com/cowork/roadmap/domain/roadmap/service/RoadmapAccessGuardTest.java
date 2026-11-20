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
import com.cowork.roadmap.global.team.projection.readiness.ProjectionReadiness;
import com.cowork.roadmap.global.team.repository.RoadmapPolicyRepository;
import com.cowork.roadmap.global.team.repository.TeamMemberProjectionRepository;

import team.themoment.sdk.exception.ExpectedException;

class RoadmapAccessGuardTest {

    private final TeamMemberProjectionRepository teamMemberships = mock(TeamMemberProjectionRepository.class);
    private final RoadmapPolicyRepository policies = mock(RoadmapPolicyRepository.class);
    private final ProjectionReadiness projectionReadiness = mock(ProjectionReadiness.class);
    private final RoadmapAccessGuard accessGuard = new RoadmapAccessGuard(teamMemberships,
            policies,
            projectionReadiness,
            projectionReadiness,
            projectionReadiness);

    RoadmapAccessGuardTest() {
        when(projectionReadiness.isReady()).thenReturn(true);
    }

    @Nested
    class RequireMutable {

        @Test
        void globalScopeWithoutPolicy_isForbidden() {
            Roadmap roadmap = roadmap(RoadmapScope.GLOBAL, 1L, null);

            ExpectedException error = assertThrows(ExpectedException.class,
                    () -> accessGuard.requireMutable(roadmap, 1L));
            assertTrue(error.getStatusCode() == HttpStatus.FORBIDDEN);
            verifyNoInteractions(teamMemberships);
        }

        @Test
        void globalPolicyGrantAndRevocation_applyToSameUser() {
            Roadmap roadmap = roadmap(RoadmapScope.GLOBAL, 1L, null);

            when(policies.allows(0, 1, "manage_global_roadmaps")).thenReturn(true, false);
            accessGuard.requireMutable(roadmap, 1L);
            assertThrows(ExpectedException.class, () -> accessGuard.requireMutable(roadmap, 1L));
            verifyNoInteractions(teamMemberships);
        }

        @Test
        void customScopeByActiveCreator_succeeds() {
            Roadmap roadmap = roadmap(RoadmapScope.TEAM, 7L, 99L);
            when(teamMemberships.findActiveRole(99L, 7L)).thenReturn(Optional.of("MEMBER"));

            accessGuard.requireMutable(roadmap, 7L);
        }

        @Test
        void customScopeByLeftCreator_isForbidden() {
            Roadmap roadmap = roadmap(RoadmapScope.TEAM, 7L, 99L);
            when(teamMemberships.findActiveRole(99L, 7L)).thenReturn(Optional.empty());

            ExpectedException error = assertThrows(ExpectedException.class,
                    () -> accessGuard.requireMutable(roadmap, 7L));
            assertTrue(error.getStatusCode() == HttpStatus.FORBIDDEN);
        }

        @Test
        void customScopeByActiveManager_succeeds() {
            Roadmap roadmap = roadmap(RoadmapScope.TEAM, 7L, 99L);
            when(teamMemberships.findActiveRole(99L, 8L)).thenReturn(Optional.of("MEMBER"));
            when(policies.allows(99, 8, "manage_team")).thenReturn(true);

            accessGuard.requireMutable(roadmap, 8L);
        }

        @Test
        void customScopeByCreator_whenProjectionNotReady_isUnavailable() {
            Roadmap roadmap = roadmap(RoadmapScope.TEAM, 7L, 99L);
            when(projectionReadiness.isReady()).thenReturn(false);

            ExpectedException error = assertThrows(ExpectedException.class,
                    () -> accessGuard.requireMutable(roadmap, 7L));
            assertTrue(error.getStatusCode() == HttpStatus.SERVICE_UNAVAILABLE);
            verifyNoInteractions(teamMemberships);
        }

        @Test
        void customScopeByTeamMember_isForbidden() {
            Roadmap roadmap = roadmap(RoadmapScope.TEAM, 7L, 99L);
            when(teamMemberships.findActiveRole(99L, 8L)).thenReturn(Optional.of("MEMBER"));

            ExpectedException error = assertThrows(ExpectedException.class,
                    () -> accessGuard.requireMutable(roadmap, 8L));
            assertTrue(error.getStatusCode() == HttpStatus.FORBIDDEN);
        }
    }

    @Test
    void managementGrantIsScopedToTheRoadmapOwnerTeam() {
        when(teamMemberships.findActiveRole(10L, 7L)).thenReturn(Optional.of("MEMBER"));
        when(teamMemberships.findActiveRole(20L, 7L)).thenReturn(Optional.of("ADMIN"));
        when(policies.allows(10, 7, "manage_team")).thenReturn(true);

        accessGuard.requireCreatable(7L, RoadmapScope.TEAM, 10L);
        var error = assertThrows(ExpectedException.class,
                () -> accessGuard.requireCreatable(7L, RoadmapScope.TEAM, 20L));
        assertTrue(error.getStatusCode() == HttpStatus.FORBIDDEN);
    }

    @Test
    void staleAccountPolicyRejectsGlobalManagementBeforeReadingGrants() {
        when(projectionReadiness.isReady()).thenReturn(false);
        var error = assertThrows(ExpectedException.class,
                () -> accessGuard.requireCreatable(7L, RoadmapScope.GLOBAL, null));
        assertTrue(error.getStatusCode() == HttpStatus.SERVICE_UNAVAILABLE);
        verifyNoInteractions(policies);
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
    class RequireAssignableMember {

        @Test
        void activeMember_succeeds() {
            when(teamMemberships.findActiveRole(99L, 42L)).thenReturn(Optional.of("MEMBER"));

            accessGuard.requireAssignableMember(99L, 42L);
        }

        @Test
        void memberCommittedBeforeSecondLookup_succeeds() {
            when(teamMemberships.findActiveRole(99L, 42L)).thenReturn(Optional.empty(), Optional.of("MEMBER"));

            accessGuard.requireAssignableMember(99L, 42L);
        }

        @Test
        void nonMember_isBadRequest() {
            when(teamMemberships.findActiveRole(99L, 42L)).thenReturn(Optional.empty());

            ExpectedException error = assertThrows(ExpectedException.class,
                    () -> accessGuard.requireAssignableMember(99L, 42L));
            assertTrue(error.getStatusCode() == HttpStatus.BAD_REQUEST);
        }

        @Test
        void projectionNotReady_isUnavailable() {
            when(projectionReadiness.isReady()).thenReturn(false);

            ExpectedException error = assertThrows(ExpectedException.class,
                    () -> accessGuard.requireAssignableMember(99L, 42L));
            assertTrue(error.getStatusCode() == HttpStatus.SERVICE_UNAVAILABLE);
            verifyNoInteractions(teamMemberships);
        }
    }

    @Nested
    class RequireCreatable {

        @Test
        void teamScopeByOwner_succeeds() {
            when(teamMemberships.findActiveRole(99L, 7L)).thenReturn(Optional.of("OWNER"));
            when(policies.allows(99, 7, "administrator")).thenReturn(true);

            accessGuard.requireCreatable(7L, RoadmapScope.TEAM, 99L);
        }
    }

    @Nested
    class RequireReadable {

        @Test
        void globalScope_succeeds() {
            Roadmap roadmap = roadmap(RoadmapScope.GLOBAL, 1L, null);

            accessGuard.requireReadable(roadmap, 123L);
        }

        @Test
        void customScopeByTeamMember_succeeds() {
            Roadmap roadmap = roadmap(RoadmapScope.TEAM, 1L, 99L);
            when(teamMemberships.findActiveRole(99L, 123L)).thenReturn(Optional.of("MEMBER"));

            accessGuard.requireReadable(roadmap, 123L);
        }

        @Test
        void customScopeByOutsider_isForbidden() {
            Roadmap roadmap = roadmap(RoadmapScope.TEAM, 1L, 99L);
            when(teamMemberships.findActiveRole(99L, 123L)).thenReturn(Optional.empty());

            ExpectedException error = assertThrows(ExpectedException.class,
                    () -> accessGuard.requireReadable(roadmap, 123L));
            assertTrue(error.getStatusCode() == HttpStatus.FORBIDDEN);
        }
    }

    private static Roadmap roadmap(RoadmapScope scope, Long createdBy, Long ownerTeamId) {
        Roadmap roadmap = Roadmap.builder().id(1L).scope(scope.name()).ownerTeamId(ownerTeamId).build();
        roadmap.setCreatedBy(createdBy);
        return roadmap;
    }
}
