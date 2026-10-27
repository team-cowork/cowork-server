package com.cowork.roadmap.domain.roadmap.service;

import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.cowork.roadmap.domain.roadmap.entity.Roadmap;
import com.cowork.roadmap.domain.roadmap.entity.RoadmapScope;
import com.cowork.roadmap.global.team.TeamMemberProjectionReadiness;
import com.cowork.roadmap.global.team.TeamMemberProjectionRepository;

import lombok.RequiredArgsConstructor;
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
    public void requireCreatable(Long userId, String userRole, RoadmapScope scope, Long ownerTeamId) {
        if (scope == RoadmapScope.GLOBAL) {
            requireGlobalAdmin(userRole);
            return;
        }
        if (ownerTeamId == null) {
            throw new ExpectedException("커스텀 로드맵에는 owner_team_id가 필요합니다.", HttpStatus.BAD_REQUEST);
        }
        requireTeamManager(ownerTeamId, userId, "커스텀 로드맵은 팀 OWNER/ADMIN만 생성할 수 있습니다.");
    }

    /** 로드맵 변경/삭제 권한. GLOBAL은 ADMIN, 커스텀은 생성자 또는 팀 OWNER/ADMIN. */
    public void requireMutable(Roadmap roadmap, Long userId, String userRole) {
        if (RoadmapScope.GLOBAL.name().equals(roadmap.getScope())) {
            requireGlobalAdmin(userRole);
            return;
        }
        if (!userId.equals(roadmap.getCreatedBy())) {
            requireTeamManager(roadmap.getOwnerTeamId(), userId, "로드맵을 수정할 권한이 없습니다.");
        }
    }

    /** 로드맵 읽기 권한. GLOBAL은 모두, 커스텀은 ADMIN 또는 소유 팀 멤버. */
    public void requireReadable(Roadmap roadmap, Long userId, String userRole) {
        if (RoadmapScope.GLOBAL.name().equals(roadmap.getScope()) || ROLE_ADMIN.equals(userRole)) {
            return;
        }
        requireTeamRole(roadmap.getOwnerTeamId(), userId, ignored -> true, "로드맵을 조회할 권한이 없습니다.");
    }

    /** 팀별 목록을 조회할 권한. ADMIN 또는 해당 팀의 활성 멤버만 허용. */
    public void requireTeamReadable(Long teamId, Long userId, String userRole) {
        if (!ROLE_ADMIN.equals(userRole)) {
            requireTeamRole(teamId, userId, ignored -> true, "로드맵을 조회할 권한이 없습니다.");
        }
    }

    /** 과제 목록은 공용 콘텐츠의 공개 범위와 별개로 과제 소유 팀에 제한한다. */
    public boolean canReadTeam(Long teamId, Long userId, String userRole) {
        if (ROLE_ADMIN.equals(userRole)) {
            return true;
        }
        if (teamId == null) {
            return false;
        }
        requireProjectionReady();
        return teamMemberships.findActiveRole(teamId, userId).isPresent();
    }

    /** 과제 출제/삭제 권한. 글로벌 ADMIN 또는 해당 팀의 OWNER/ADMIN. */
    public void requireTeamManagerOrAdmin(Long userId, String userRole, Long teamId) {
        if (!ROLE_ADMIN.equals(userRole)) {
            requireTeamManager(teamId, userId, "팀 OWNER/ADMIN만 수행할 수 있습니다.");
        }
    }

    private void requireGlobalAdmin(String userRole) {
        if (!ROLE_ADMIN.equals(userRole)) {
            throw new ExpectedException("글로벌 로드맵은 ADMIN만 변경할 수 있습니다.", HttpStatus.FORBIDDEN);
        }
    }

    private void requireTeamManager(Long teamId, Long userId, String message) {
        requireTeamRole(teamId, userId, TEAM_MANAGER_ROLES::contains, message);
    }

    private void requireTeamRole(Long teamId, Long userId, Predicate<String> accepted, String message) {
        requireProjectionReady();
        if (findAcceptedRole(teamId, userId, accepted).isPresent()) {
            return;
        }
        requireProjectionReady();
        if (findAcceptedRole(teamId, userId, accepted).isEmpty()) {
            throw new ExpectedException(message, HttpStatus.FORBIDDEN);
        }
    }

    private Optional<String> findAcceptedRole(Long teamId, Long userId, Predicate<String> accepted) {
        return teamMemberships.findActiveRole(teamId, userId).filter(accepted);
    }

    private void requireProjectionReady() {
        if (!projectionReadiness.isReady()) {
            throw new ExpectedException("팀 멤버 투영을 동기화하는 중입니다.", HttpStatus.SERVICE_UNAVAILABLE);
        }
    }
}
