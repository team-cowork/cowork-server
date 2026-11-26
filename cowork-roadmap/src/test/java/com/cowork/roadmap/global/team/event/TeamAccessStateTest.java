package com.cowork.roadmap.global.team.event;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

class TeamAccessStateTest {
    @Test
    void usesThePublishedRoadmapDecisionForTheKeyedMember() {
        var event = JsonMapper.builder()
                .build()
                .readTree(
                        """
                                {"eventType":"UPSERT","teamId":9,"accountId":7,"permissions":{"administrator":true,"manage_roadmaps":false},
                                 "sourceMembershipVersion":"2026-10-10T12:00:00Z","sourceAuthorizationVersion":"2026-10-10T12:00:00Z",
                                 "occurredAt":"2026-10-10T12:00:01Z"}
                                """);
        assertFalse(TeamAccessState.parse("9:7", event).manageRoadmaps());
        assertThrows(IllegalArgumentException.class, () -> TeamAccessState.parse("8:7", event));
    }
}
