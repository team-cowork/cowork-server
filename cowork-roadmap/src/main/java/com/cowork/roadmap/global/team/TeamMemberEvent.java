package com.cowork.roadmap.global.team;

import java.time.Instant;

public record TeamMemberEvent(String eventType, Long teamId, Long userId, String role, String teamName,
        Instant occurredAt) {
}
