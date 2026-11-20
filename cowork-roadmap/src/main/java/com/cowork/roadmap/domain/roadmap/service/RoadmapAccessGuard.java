package com.cowork.roadmap.domain.roadmap.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.cowork.roadmap.domain.roadmap.entity.Roadmap;
import com.cowork.roadmap.domain.roadmap.entity.RoadmapScope;
import com.cowork.roadmap.global.team.projection.readiness.ProjectionReadiness;
import com.cowork.roadmap.global.team.repository.RoadmapPolicyRepository;
import com.cowork.roadmap.global.team.repository.TeamMemberProjectionRepository;

import team.themoment.sdk.exception.ExpectedException;

@Component
public class RoadmapAccessGuard {
    private final TeamMemberProjectionRepository memberships;
    private final RoadmapPolicyRepository policies;
    private final ProjectionReadiness members;
    private final ProjectionReadiness accounts;
    private final ProjectionReadiness teams;

    public RoadmapAccessGuard(TeamMemberProjectionRepository memberships,
            RoadmapPolicyRepository policies,
            @Qualifier("teamMemberProjectionReadiness") ProjectionReadiness members,
            @Qualifier("accountPolicyReadiness") ProjectionReadiness accounts,
            @Qualifier("teamPolicyReadiness") ProjectionReadiness teams) {
        this.memberships = memberships;
        this.policies = policies;
        this.members = members;
        this.accounts = accounts;
        this.teams = teams;
    }

    public void requireCreatable(Long userId, RoadmapScope scope, Long ownerTeamId) {
        if (scope == RoadmapScope.GLOBAL) {
            requireGlobalManagement(userId);
        } else {
            requireTeamManager(userId, ownerTeamId);
        }
    }

    public void requireMutable(Roadmap roadmap, Long userId) {
        if (RoadmapScope.GLOBAL.name().equals(roadmap.getScope())) {
            requireGlobalManagement(userId);
        } else if (userId.equals(roadmap.getCreatedBy())) {
            requireTeamMember(roadmap.getOwnerTeamId(), userId);
        } else {
            requireTeamManager(userId, roadmap.getOwnerTeamId());
        }
    }

    public void requireReadable(Roadmap roadmap, Long userId) {
        if (!RoadmapScope.GLOBAL.name().equals(roadmap.getScope())) {
            requireTeamReadable(roadmap.getOwnerTeamId(), userId);
        }
    }

    public void requireTeamReadable(Long teamId, Long userId) {
        if (!canReadTeam(teamId, userId)) {
            throw new ExpectedException("로드맵을 조회할 권한이 없습니다.", HttpStatus.FORBIDDEN);
        }
    }

    public boolean canReadTeam(Long teamId, Long userId) {
        return accountAllows(userId, "read_all_team_roadmaps") || (teamId != null && activeMember(teamId, userId));
    }

    public void requireAssignmentCreation(Long userId, Long teamId) {
        if (!accountAllows(userId, "create_team_assignments")) {
            requireTeamManager(userId, teamId);
        }
    }

    public boolean accountAllows(Long userId, String action) {
        requireReady(accounts);
        return policies.allows(0, userId, action);
    }

    public void requireTeamMember(Long teamId, Long userId) {
        if (!activeMember(teamId, userId)) {
            throw new ExpectedException("팀 멤버만 수행할 수 있습니다.", HttpStatus.FORBIDDEN);
        }
    }

    public void requireAssignableMember(Long teamId, Long assigneeUserId) {
        if (!activeMember(teamId, assigneeUserId)) {
            throw new ExpectedException("과제 담당자는 팀의 활성 멤버여야 합니다.", HttpStatus.BAD_REQUEST);
        }
    }

    public void requireTeamManager(Long userId, Long teamId) {
        requireTeamMember(teamId, userId);
        requireReady(teams);
        if (!policies.allows(teamId, userId, "administrator") && !policies.allows(teamId, userId, "manage_team")) {
            throw new ExpectedException("팀 관리 권한이 필요합니다.", HttpStatus.FORBIDDEN);
        }
    }

    private void requireGlobalManagement(Long userId) {
        if (!accountAllows(userId, "manage_global_roadmaps")) {
            throw new ExpectedException("글로벌 로드맵을 관리할 권한이 없습니다.", HttpStatus.FORBIDDEN);
        }
    }

    private boolean activeMember(Long teamId, Long userId) {
        requireReady(members);
        if (memberships.findActiveRole(teamId, userId).isPresent())
            return true;
        requireReady(members);
        return memberships.findActiveRole(teamId, userId).isPresent();
    }

    private static void requireReady(ProjectionReadiness readiness) {
        if (!readiness.isReady()) {
            throw new ExpectedException("권한 정보를 동기화하는 중입니다.", HttpStatus.SERVICE_UNAVAILABLE);
        }
    }
}
