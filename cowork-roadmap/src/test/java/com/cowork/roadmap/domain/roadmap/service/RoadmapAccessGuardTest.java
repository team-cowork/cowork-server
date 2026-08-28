package com.cowork.roadmap.domain.roadmap.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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

    @Test
    void requireMutable_globalScopeByNonAdmin_isForbidden() {
        Roadmap roadmap = roadmap(RoadmapScope.GLOBAL, 1L, null);

        StepVerifier.create(accessGuard.requireMutable(roadmap, 1L, "MEMBER"))
                .expectErrorMatches(error -> error instanceof ExpectedException expected
                        && expected.getStatusCode() == HttpStatus.FORBIDDEN)
                .verify();
        verifyNoInteractions(teamMemberships);
    }

    @Test
    void requireMutable_globalScopeByAdmin_succeeds() {
        Roadmap roadmap = roadmap(RoadmapScope.GLOBAL, 1L, null);

        StepVerifier.create(accessGuard.requireMutable(roadmap, 1L, "ADMIN")).verifyComplete();
        verifyNoInteractions(teamMemberships);
    }

    @Test
    void requireMutable_customScopeByCreator_succeedsWithoutTeamCall() {
        Roadmap roadmap = roadmap(RoadmapScope.TEAM, 7L, 99L);

        StepVerifier.create(accessGuard.requireMutable(roadmap, 7L, "MEMBER")).verifyComplete();
        verifyNoInteractions(teamMemberships);
    }

    @Test
    void requireMutable_customScopeByTeamMember_isForbidden() {
        Roadmap roadmap = roadmap(RoadmapScope.TEAM, 7L, 99L);
        when(teamMemberships.findActiveRole(99L, 8L)).thenReturn(Mono.just("MEMBER"));

        StepVerifier.create(accessGuard.requireMutable(roadmap, 8L, "MEMBER"))
                .expectErrorMatches(error -> error instanceof ExpectedException expected
                        && expected.getStatusCode() == HttpStatus.FORBIDDEN)
                .verify();
    }

    @Test
    void requireReadable_globalScope_succeeds() {
        Roadmap roadmap = roadmap(RoadmapScope.GLOBAL, 1L, null);

        StepVerifier.create(accessGuard.requireReadable(roadmap, 123L, "MEMBER")).verifyComplete();
    }

    @Test
    void requireReadable_customScopeWhileProjectionCatchesUp_isUnavailable() {
        Roadmap roadmap = roadmap(RoadmapScope.TEAM, 1L, 99L);
        when(projectionReadiness.isReady()).thenReturn(false);

        StepVerifier.create(accessGuard.requireReadable(roadmap, 123L, "MEMBER"))
                .expectErrorMatches(error -> error instanceof ExpectedException expected
                        && expected.getStatusCode() == HttpStatus.SERVICE_UNAVAILABLE)
                .verify();
        verifyNoInteractions(teamMemberships);
    }

    @Test
    void requireMutable_rechecksCurrentProjectionBeforeForbiddingManager() {
        Roadmap roadmap = roadmap(RoadmapScope.TEAM, 7L, 99L);
        when(teamMemberships.findActiveRole(99L, 8L)).thenReturn(Mono.empty(), Mono.just("ADMIN"));

        StepVerifier.create(accessGuard.requireMutable(roadmap, 8L, "MEMBER")).verifyComplete();
    }

    private static Roadmap roadmap(RoadmapScope scope, Long createdBy, Long ownerTeamId) {
        Roadmap roadmap = Roadmap.builder().id(1L).scope(scope.name()).ownerTeamId(ownerTeamId).build();
        roadmap.setCreatedBy(createdBy);
        return roadmap;
    }
}
