package com.cowork.roadmap.domain.roadmap.service;

import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.cowork.roadmap.domain.roadmap.entity.Roadmap;
import com.cowork.roadmap.domain.roadmap.entity.RoadmapScope;
import com.cowork.roadmap.global.client.TeamClient;

import reactor.core.publisher.Mono;
import team.themoment.sdk.exception.ExpectedException;

/**
 * 로드맵 읽기/변경/생성 권한 판정. 커스텀(TEAM/PROJECT) 로드맵은 항상 owner_team_id를 기준으로
 * cowork-team의 팀 역할을 조회해 판정한다.
 */
@Component
public class RoadmapAccessGuard {

    private static final String ROLE_ADMIN = "ADMIN";
    private static final Set<String> TEAM_MANAGER_ROLES = Set.of("OWNER", "ADMIN");

    private final TeamClient teamClient;

    public RoadmapAccessGuard(TeamClient teamClient) {
        this.teamClient = teamClient;
    }

    /** 로드맵 생성 권한. GLOBAL은 ADMIN, 커스텀은 소유 팀의 OWNER/ADMIN만 허용. */
    public Mono<Void> requireCreatable(Long userId, String userRole, RoadmapScope scope, Long ownerTeamId) {
        if (scope == RoadmapScope.GLOBAL) {
            return requireGlobalAdmin(userRole);
        }
        if (ownerTeamId == null) {
            return Mono.error(new ExpectedException("커스텀 로드맵에는 owner_team_id가 필요합니다.", HttpStatus.BAD_REQUEST));
        }
        return requireTeamManager(ownerTeamId, userId, "커스텀 로드맵은 팀 OWNER/ADMIN만 생성할 수 있습니다.");
    }

    /** 로드맵 변경/삭제 권한. GLOBAL은 ADMIN, 커스텀은 생성자 또는 팀 OWNER/ADMIN. */
    public Mono<Void> requireMutable(Roadmap roadmap, Long userId, String userRole) {
        if (RoadmapScope.GLOBAL.name().equals(roadmap.getScope())) {
            return requireGlobalAdmin(userRole);
        }
        if (userId.equals(roadmap.getCreatedBy())) {
            return Mono.empty();
        }
        return requireTeamManager(roadmap.getOwnerTeamId(), userId, "로드맵을 수정할 권한이 없습니다.");
    }

    /** 로드맵 읽기 권한. GLOBAL은 모두, 커스텀은 ADMIN 또는 소유 팀 멤버. */
    public Mono<Void> requireReadable(Roadmap roadmap, Long userId, String userRole) {
        if (RoadmapScope.GLOBAL.name().equals(roadmap.getScope())) {
            return Mono.empty();
        }
        if (ROLE_ADMIN.equals(userRole)) {
            return Mono.empty();
        }
        return teamClient.getMemberRole(roadmap.getOwnerTeamId(), userId)
                .switchIfEmpty(Mono.error(new ExpectedException("로드맵을 조회할 권한이 없습니다.", HttpStatus.FORBIDDEN)))
                .then();
    }

    /** 과제 출제/삭제 권한. 글로벌 ADMIN 또는 해당 팀의 OWNER/ADMIN. */
    public Mono<Void> requireTeamManagerOrAdmin(Long userId, String userRole, Long teamId) {
        if (ROLE_ADMIN.equals(userRole)) {
            return Mono.empty();
        }
        return requireTeamManager(teamId, userId, "팀 OWNER/ADMIN만 수행할 수 있습니다.");
    }

    private Mono<Void> requireGlobalAdmin(String userRole) {
        if (ROLE_ADMIN.equals(userRole)) {
            return Mono.empty();
        }
        return Mono.error(new ExpectedException("글로벌 로드맵은 ADMIN만 변경할 수 있습니다.", HttpStatus.FORBIDDEN));
    }

    private Mono<Void> requireTeamManager(Long teamId, Long userId, String message) {
        return teamClient.getMemberRole(teamId, userId)
                .filter(TEAM_MANAGER_ROLES::contains)
                .switchIfEmpty(Mono.error(new ExpectedException(message, HttpStatus.FORBIDDEN)))
                .then();
    }
}
