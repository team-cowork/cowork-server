package com.cowork.roadmap.global.team.repository;

import java.util.Optional;

import com.cowork.roadmap.global.team.event.TeamMemberEvent;

public interface TeamMemberProjectionRepository {

    Optional<String> findActiveRole(Long teamId, Long userId);

    boolean allActiveMembersHaveAuthorizationVersion();

    void upsert(TeamMemberEvent event);

    void deactivate(TeamMemberEvent event);
}
