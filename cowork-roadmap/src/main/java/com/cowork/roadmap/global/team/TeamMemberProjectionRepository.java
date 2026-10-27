package com.cowork.roadmap.global.team;

import java.util.Optional;

public interface TeamMemberProjectionRepository {

    Optional<String> findActiveRole(Long teamId, Long userId);

    void upsert(TeamMemberEvent event);

    void deactivate(TeamMemberEvent event);
}
