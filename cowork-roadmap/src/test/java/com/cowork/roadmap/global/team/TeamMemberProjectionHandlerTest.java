package com.cowork.roadmap.global.team;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.time.Instant;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class TeamMemberProjectionHandlerTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-01-01T00:00:00Z");

    private final TeamMemberProjectionRepository repository = mock(TeamMemberProjectionRepository.class);
    private final TeamMemberProjectionHandler handler = new TeamMemberProjectionHandler(repository);

    @Test
    void upsert_upsertsMembership() {
        TeamMemberEvent event = event("UPSERT");

        handler.apply(event);

        verify(repository).upsert(event);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void delete_deactivatesMembership() {
        TeamMemberEvent event = event("DELETE");

        handler.apply(event);

        verify(repository).deactivate(event);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void unsupportedEventType_isRejectedWithoutWriting() {
        assertThrows(IllegalArgumentException.class, () -> handler.apply(event("RENAME")));

        verifyNoInteractions(repository);
    }

    @ParameterizedTest
    @MethodSource("invalidEvents")
    void invalidPayload_isRejectedWithoutWriting(TeamMemberEvent event) {
        assertThrows(IllegalArgumentException.class, () -> handler.apply(event));

        verifyNoInteractions(repository);
    }

    static Stream<TeamMemberEvent> invalidEvents() {
        return Stream.of(null,
                new TeamMemberEvent(null, 1L, 2L, "MEMBER", "team", OCCURRED_AT),
                new TeamMemberEvent("UPSERT", null, 2L, "MEMBER", "team", OCCURRED_AT),
                new TeamMemberEvent("UPSERT", 0L, 2L, "MEMBER", "team", OCCURRED_AT),
                new TeamMemberEvent("UPSERT", 1L, null, "MEMBER", "team", OCCURRED_AT),
                new TeamMemberEvent("UPSERT", 1L, 0L, "MEMBER", "team", OCCURRED_AT),
                new TeamMemberEvent("UPSERT", 1L, 2L, null, "team", OCCURRED_AT),
                new TeamMemberEvent("UPSERT", 1L, 2L, "GUEST", "team", OCCURRED_AT),
                new TeamMemberEvent("UPSERT", 1L, 2L, "MEMBER", null, OCCURRED_AT),
                new TeamMemberEvent("UPSERT", 1L, 2L, "MEMBER", " ", OCCURRED_AT),
                new TeamMemberEvent("UPSERT", 1L, 2L, "MEMBER", "team", null));
    }

    private static TeamMemberEvent event(String eventType) {
        return new TeamMemberEvent(eventType, 1L, 2L, "MEMBER", "team", OCCURRED_AT);
    }
}
