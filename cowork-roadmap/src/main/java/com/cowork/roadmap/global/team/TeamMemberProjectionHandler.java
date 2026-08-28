package com.cowork.roadmap.global.team;

import java.util.Set;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
public class TeamMemberProjectionHandler {

    private static final Set<String> ROLES = Set.of("OWNER", "ADMIN", "MEMBER");

    private final TeamMemberProjectionRepository repository;

    public Mono<Void> apply(TeamMemberEvent event) {
        if (!isValid(event)) {
            return Mono.error(new IllegalArgumentException("Invalid team.member.event payload"));
        }
        return switch (event.eventType()) {
            case "UPSERT" -> repository.upsert(event);
            case "DELETE" -> repository.deactivate(event);
            default -> Mono.error(new IllegalArgumentException("Unsupported team member event: " + event.eventType()));
        };
    }

    private boolean isValid(TeamMemberEvent event) {
        return event != null && event.teamId() != null && event.teamId() > 0 && event.userId() != null
                && event.userId() > 0 && ROLES.contains(event.role()) && event.teamName() != null
                && !event.teamName().isBlank() && event.occurredAt() != null;
    }
}
