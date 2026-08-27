package com.cowork.roadmap.domain.roadmap.service;

import java.util.Set;
import java.util.function.Predicate;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.cowork.roadmap.domain.roadmap.entity.Roadmap;
import com.cowork.roadmap.domain.roadmap.entity.RoadmapScope;
import com.cowork.roadmap.global.team.TeamMemberProjectionReadiness;
import com.cowork.roadmap.global.team.TeamMemberProjectionRepository;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;
import team.themoment.sdk.exception.ExpectedException;

/**
 * 로드맵 읽기/변경/생성 권한 판정. 커스텀(TEAM/PROJECT) 로드맵은 항상 owner_team_id를 기준으로 Kafka로 동기화한
 * 로컬 팀 멤버 투영의 역할을 조회해 판정한다.
 */
@Component
@RequiredArgsConstructor
public class RoadmapAccessGuard {

    private static final String ROLE_ADMIN = "ADMIN";
    private static final Set<String> TEAM_MANAGER_ROLES = Set.of("OWNER", "ADMIN");

    private final TeamMemberProjectionRepository teamMemberships;
    private final TeamMemberProjectionReadiness projectionReadiness;

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
        return requireTeamRole(roadmap.getOwnerTeamId(), userId, ignored -> true, "로드맵을 조회할 권한이 없습니다.");
    }

    /** 팀별 목록을 조회할 권한. ADMIN 또는 해당 팀의 활성 멤버만 허용. */
    public Mono<Void> requireTeamReadable(Long teamId, Long userId, String userRole) {
        if (ROLE_ADMIN.equals(userRole)) {
            return Mono.empty();
        }
        return requireTeamRole(teamId, userId, ignored -> true, "로드맵을 조회할 권한이 없습니다.");
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
        return requireTeamRole(teamId, userId, TEAM_MANAGER_ROLES::contains, message);
    }

    private Mono<Void> requireTeamRole(Long teamId, Long userId, Predicate<String> accepted, String message) {
        return requireProjectionReady().then(Mono.defer(() -> findAcceptedRole(teamId, userId, accepted)))
                .switchIfEmpty(
                        Mono.defer(() -> requireProjectionReady().then(findAcceptedRole(teamId, userId, accepted))))
                .switchIfEmpty(Mono.error(new ExpectedException(message, HttpStatus.FORBIDDEN)))
                .then();
    }

    private Mono<String> findAcceptedRole(Long teamId, Long userId, Predicate<String> accepted) {
        return teamMemberships.findActiveRole(teamId, userId).filter(accepted);
    }

    private Mono<Void> requireProjectionReady() {
        if (projectionReadiness.isReady()) {
            return Mono.empty();
        }
        return Mono.error(new ExpectedException("팀 멤버 투영을 동기화하는 중입니다.", HttpStatus.SERVICE_UNAVAILABLE));
    }
}
