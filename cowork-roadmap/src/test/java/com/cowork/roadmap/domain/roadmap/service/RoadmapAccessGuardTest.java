package com.cowork.roadmap.domain.roadmap.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.cowork.roadmap.domain.roadmap.entity.Roadmap;
import com.cowork.roadmap.domain.roadmap.entity.RoadmapScope;
import com.cowork.roadmap.global.client.TeamClient;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import team.themoment.sdk.exception.ExpectedException;

class RoadmapAccessGuardTest {

    private final TeamClient teamClient = mock(TeamClient.class);
    private final RoadmapAccessGuard accessGuard = new RoadmapAccessGuard(teamClient);

    @Test
    void requireMutable_globalScopeByNonAdmin_isForbidden() {
        Roadmap roadmap = roadmap(RoadmapScope.GLOBAL, 1L);

        StepVerifier.create(accessGuard.requireMutable(roadmap, 1L, "MEMBER"))
                .expectErrorMatches(error -> error instanceof ExpectedException expected
                        && expected.getStatusCode() == HttpStatus.FORBIDDEN)
                .verify();
        verifyNoInteractions(teamClient);
    }

    @Test
    void requireMutable_globalScopeByAdmin_succeeds() {
        Roadmap roadmap = roadmap(RoadmapScope.GLOBAL, 1L);

        StepVerifier.create(accessGuard.requireMutable(roadmap, 1L, "ADMIN")).verifyComplete();
        verifyNoInteractions(teamClient);
    }

    @Test
    void requireMutable_customScopeByCreator_succeedsWithoutTeamCall() {
        Roadmap roadmap = roadmap(RoadmapScope.TEAM, 7L);
        roadmap.setOwnerTeamId(99L);

        StepVerifier.create(accessGuard.requireMutable(roadmap, 7L, "MEMBER")).verifyComplete();
        verifyNoInteractions(teamClient);
    }

    @Test
    void requireMutable_customScopeByTeamMember_isForbidden() {
        Roadmap roadmap = roadmap(RoadmapScope.TEAM, 7L);
        roadmap.setOwnerTeamId(99L);
        when(teamClient.getMemberRole(99L, 8L)).thenReturn(Mono.just("MEMBER"));

        StepVerifier.create(accessGuard.requireMutable(roadmap, 8L, "MEMBER"))
                .expectErrorMatches(error -> error instanceof ExpectedException expected
                        && expected.getStatusCode() == HttpStatus.FORBIDDEN)
                .verify();
    }

    @Test
    void requireReadable_globalScope_succeeds() {
        Roadmap roadmap = roadmap(RoadmapScope.GLOBAL, 1L);

        StepVerifier.create(accessGuard.requireReadable(roadmap, 123L, "MEMBER")).verifyComplete();
    }

    private static Roadmap roadmap(RoadmapScope scope, Long createdBy) {
        Roadmap roadmap = new Roadmap();
        roadmap.setId(1L);
        roadmap.setScope(scope.name());
        roadmap.setCreatedBy(createdBy);
        return roadmap;
    }
}
