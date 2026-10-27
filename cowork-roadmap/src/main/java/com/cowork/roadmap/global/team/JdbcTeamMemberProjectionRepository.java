package com.cowork.roadmap.global.team;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class JdbcTeamMemberProjectionRepository implements TeamMemberProjectionRepository {

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

    private final JdbcClient jdbcClient;

    @Override
    public Optional<String> findActiveRole(Long teamId, Long userId) {
        return jdbcClient.sql("""
                SELECT role
                FROM tb_team_member_projections
                WHERE team_id = :teamId AND user_id = :userId AND active = TRUE
                """)
                .param("teamId", teamId)
                .param("userId", userId)
                .query((row, rowNum) -> row.getString("role"))
                .optional();
    }

    @Override
    public void upsert(TeamMemberEvent event) {
        apply(event, true);
    }

    @Override
    public void deactivate(TeamMemberEvent event) {
        apply(event, false);
    }

    private void apply(TeamMemberEvent event, boolean active) {
        jdbcClient.sql(APPLY_EVENT_SQL)
                .param("teamId", event.teamId())
                .param("userId", event.userId())
                .param("role", event.role())
                .param("teamName", event.teamName())
                .param("active", active)
                .param("occurredAt", LocalDateTime.ofInstant(event.occurredAt(), ZoneOffset.UTC))
                .update();
    }
}
