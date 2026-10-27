package com.cowork.roadmap.global.team;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class JpaTeamMemberProjectionRepository implements TeamMemberProjectionRepository {

    private static final int QUERY_TIMEOUT_MS = 10_000;

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

    private final EntityManager entityManager;

    @Override
    public Optional<String> findActiveRole(Long teamId, Long userId) {
        String role = (String) entityManager.createNativeQuery("""
                SELECT role
                FROM tb_team_member_projections
                WHERE team_id = :teamId AND user_id = :userId AND active = TRUE
                """, String.class)
                .setHint("jakarta.persistence.query.timeout", QUERY_TIMEOUT_MS)
                .setParameter("teamId", teamId)
                .setParameter("userId", userId)
                .getSingleResultOrNull();
        return Optional.ofNullable(role);
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
        entityManager.createNativeQuery(APPLY_EVENT_SQL)
                .setHint("jakarta.persistence.query.timeout", QUERY_TIMEOUT_MS)
                .setParameter("teamId", event.teamId())
                .setParameter("userId", event.userId())
                .setParameter("role", event.role())
                .setParameter("teamName", event.teamName())
                .setParameter("active", active)
                .setParameter("occurredAt", LocalDateTime.ofInstant(event.occurredAt(), ZoneOffset.UTC))
                .executeUpdate();
    }
}
