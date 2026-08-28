package com.cowork.roadmap.global.team;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class TeamMemberProjectionHandlerTest {

    private final TeamMemberProjectionRepository repository = mock(TeamMemberProjectionRepository.class);
    private final TeamMemberProjectionHandler handler = new TeamMemberProjectionHandler(repository);

    @Test
    void apply_upsertEvent_storesActiveMembership() {
        Instant occurredAt = Instant.parse("2026-08-26T12:34:56Z");
        TeamMemberEvent event = new TeamMemberEvent("UPSERT", 11L, 22L, "ADMIN", "platform", occurredAt);
        when(repository.upsert(event)).thenReturn(Mono.empty());

        StepVerifier.create(handler.apply(event)).verifyComplete();

        verify(repository).upsert(event);
    }

    @Test
    void apply_deleteEvent_storesInactiveTombstone() {
        Instant occurredAt = Instant.parse("2026-08-26T12:34:56Z");
        TeamMemberEvent event = new TeamMemberEvent("DELETE", 11L, 22L, "MEMBER", "platform", occurredAt);
        when(repository.deactivate(event)).thenReturn(Mono.empty());

        StepVerifier.create(handler.apply(event)).verifyComplete();

        verify(repository).deactivate(event);
    }

    @Test
    void apply_unknownEvent_rejectsWithoutMutation() {
        TeamMemberEvent event = new TeamMemberEvent("UNKNOWN", 11L, 22L, "MEMBER", "platform", Instant.now());

        StepVerifier.create(handler.apply(event)).expectError(IllegalArgumentException.class).verify();

        verifyNoInteractions(repository);
    }
}
