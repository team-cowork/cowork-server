package com.cowork.roadmap.domain.roadmap.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.cowork.roadmap.domain.roadmap.entity.Roadmap;
import com.cowork.roadmap.domain.roadmap.entity.RoadmapScope;
import com.cowork.roadmap.global.team.TeamMemberProjectionReadiness;
import com.cowork.roadmap.global.team.TeamMemberProjectionRepository;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
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

            StepVerifier.create(accessGuard.requireMutable(roadmap, 1L, "MEMBER"))
                    .expectErrorMatches(error -> error instanceof ExpectedException expected
                            && expected.getStatusCode() == HttpStatus.FORBIDDEN)
                    .verify();
            verifyNoInteractions(teamMemberships);
        }

        @Test
        void globalScopeByAdmin_succeeds() {
            Roadmap roadmap = roadmap(RoadmapScope.GLOBAL, 1L, null);

            StepVerifier.create(accessGuard.requireMutable(roadmap, 1L, "ADMIN")).verifyComplete();
            verifyNoInteractions(teamMemberships);
        }

        @Test
        void customScopeByCreator_succeedsWithoutTeamCall() {
            Roadmap roadmap = roadmap(RoadmapScope.TEAM, 7L, 99L);

            StepVerifier.create(accessGuard.requireMutable(roadmap, 7L, "MEMBER")).verifyComplete();
            verifyNoInteractions(teamMemberships);
        }

        @Test
        void customScopeByTeamMember_isForbidden() {
            Roadmap roadmap = roadmap(RoadmapScope.TEAM, 7L, 99L);
            when(teamMemberships.findActiveRole(99L, 8L)).thenReturn(Mono.just("MEMBER"));

            StepVerifier.create(accessGuard.requireMutable(roadmap, 8L, "MEMBER"))
                    .expectErrorMatches(error -> error instanceof ExpectedException expected
                            && expected.getStatusCode() == HttpStatus.FORBIDDEN)
                    .verify();
        }
    }

    @Nested
    class RequireCreatable {

        @Test
        void teamScopeByOwner_succeeds() {
            when(teamMemberships.findActiveRole(99L, 7L)).thenReturn(Mono.just("OWNER"));

            StepVerifier.create(accessGuard.requireCreatable(7L, "MEMBER", RoadmapScope.TEAM, 99L)).verifyComplete();
        }
    }

    @Nested
    class RequireReadable {

        @Test
        void globalScope_succeeds() {
            Roadmap roadmap = roadmap(RoadmapScope.GLOBAL, 1L, null);

            StepVerifier.create(accessGuard.requireReadable(roadmap, 123L, "MEMBER")).verifyComplete();
        }

        @Test
        void customScopeByTeamMember_succeeds() {
            Roadmap roadmap = roadmap(RoadmapScope.TEAM, 1L, 99L);
            when(teamMemberships.findActiveRole(99L, 123L)).thenReturn(Mono.just("MEMBER"));

            StepVerifier.create(accessGuard.requireReadable(roadmap, 123L, "MEMBER")).verifyComplete();
        }

        @Test
        void customScopeByOutsider_isForbidden() {
            Roadmap roadmap = roadmap(RoadmapScope.TEAM, 1L, 99L);
            when(teamMemberships.findActiveRole(99L, 123L)).thenReturn(Mono.empty());

            StepVerifier.create(accessGuard.requireReadable(roadmap, 123L, "MEMBER"))
                    .expectErrorMatches(error -> error instanceof ExpectedException expected
                            && expected.getStatusCode() == HttpStatus.FORBIDDEN)
                    .verify();
        }
    }

    private static Roadmap roadmap(RoadmapScope scope, Long createdBy, Long ownerTeamId) {
        Roadmap roadmap = Roadmap.builder().id(1L).scope(scope.name()).ownerTeamId(ownerTeamId).build();
        roadmap.setCreatedBy(createdBy);
        return roadmap;
    }
}
