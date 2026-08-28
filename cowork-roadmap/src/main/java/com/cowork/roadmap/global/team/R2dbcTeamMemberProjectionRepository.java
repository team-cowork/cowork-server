package com.cowork.roadmap.global.team;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

@Repository
@RequiredArgsConstructor
public class R2dbcTeamMemberProjectionRepository implements TeamMemberProjectionRepository {

    private static final String APPLY_EVENT_SQL = """
            INSERT INTO tb_team_member_projections
                (team_id, user_id, role, team_name, active, event_occurred_at)
            VALUES (:teamId, :userId, :role, :teamName, :active, :occurredAt)
            ON DUPLICATE KEY UPDATE
                role = IF(VALUES(event_occurred_at) >= event_occurred_at, VALUES(role), role),
                team_name = IF(VALUES(event_occurred_at) >= event_occurred_at, VALUES(team_name), team_name),
                active = IF(
                    VALUES(event_occurred_at) > event_occurred_at,
                    VALUES(active),
                    IF(VALUES(event_occurred_at) = event_occurred_at, active AND VALUES(active), active)
                ),
                event_occurred_at = GREATEST(event_occurred_at, VALUES(event_occurred_at))
            """;

    private final DatabaseClient databaseClient;

    @Override
    public Mono<String> findActiveRole(Long teamId, Long userId) {
        return databaseClient.sql("""
                SELECT role
                FROM tb_team_member_projections
                WHERE team_id = :teamId AND user_id = :userId AND active = TRUE
                """)
                .bind("teamId", teamId)
                .bind("userId", userId)
                .map((row, metadata) -> row.get("role", String.class))
                .one();
    }

    @Override
    public Mono<Void> upsert(TeamMemberEvent event) {
        return apply(event, true);
    }

    @Override
    public Mono<Void> deactivate(TeamMemberEvent event) {
        return apply(event, false);
    }

    private Mono<Void> apply(TeamMemberEvent event, boolean active) {
        return databaseClient.sql(APPLY_EVENT_SQL)
                .bind("teamId", event.teamId())
                .bind("userId", event.userId())
                .bind("role", event.role())
                .bind("teamName", event.teamName())
                .bind("active", active)
                .bind("occurredAt", LocalDateTime.ofInstant(event.occurredAt(), ZoneOffset.UTC))
                .fetch()
                .rowsUpdated()
                .then();
    }
}
