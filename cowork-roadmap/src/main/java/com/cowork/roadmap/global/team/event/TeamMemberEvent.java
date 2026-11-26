package com.cowork.roadmap.global.team.event;

import java.time.Instant;

public record TeamMemberEvent(String eventType, Long teamId, Long userId, String role, String teamName,
        Instant occurredAt, Instant authorizationVersion) {
}
