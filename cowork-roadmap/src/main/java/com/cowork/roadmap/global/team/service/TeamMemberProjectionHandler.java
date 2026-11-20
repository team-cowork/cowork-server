package com.cowork.roadmap.global.team.service;

import java.util.Set;

import org.springframework.stereotype.Component;

import com.cowork.roadmap.global.team.event.TeamMemberEvent;
import com.cowork.roadmap.global.team.repository.TeamMemberProjectionRepository;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class TeamMemberProjectionHandler {

    private static final Set<String> ROLES = Set.of("OWNER", "ADMIN", "MEMBER");

    private final TeamMemberProjectionRepository teamMemberProjectionRepository;

    public void apply(TeamMemberEvent event) {
        if (!isValid(event)) {
            throw new IllegalArgumentException("Invalid team.member.event payload");
        }
        switch (event.eventType()) {
            case "UPSERT" -> teamMemberProjectionRepository.upsert(event);
            case "DELETE" -> teamMemberProjectionRepository.deactivate(event);
            default -> throw new IllegalArgumentException("Unsupported team member event: " + event.eventType());
        }
    }

    private boolean isValid(TeamMemberEvent event) {
        return event != null && event.eventType() != null && event.teamId() != null && event.teamId() > 0
                && event.userId() != null && event.userId() > 0 && event.role() != null && ROLES.contains(event.role())
                && event.teamName() != null && !event.teamName().isBlank() && event.occurredAt() != null;
    }
}
