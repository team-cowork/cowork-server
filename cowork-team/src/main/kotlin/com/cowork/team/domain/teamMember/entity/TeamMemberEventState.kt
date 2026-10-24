package com.cowork.team.domain.teamMember.entity

import com.cowork.team.domain.team.entity.Team
import com.cowork.team.domain.team.entity.nextTeamStateOccurredAt
import com.cowork.team.domain.teamRole.entity.TeamRole
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import java.io.Serializable
import java.time.Instant

data class TeamMemberEventStateId(var teamId: Long = 0, var userId: Long = 0) : Serializable

@Entity
@IdClass(TeamMemberEventStateId::class)
@Table(name = "tb_team_member_event_states")
class TeamMemberEventState(
    @Id
    @Column(name = "team_id", nullable = false)
    val teamId: Long,

    @Id
    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var role: TeamRole,

    @Column(name = "team_name", nullable = false, length = 100)
    var teamName: String,

    @Column(length = 50)
    var nickname: String?,

    @Column(nullable = false)
    var deleted: Boolean,

    @Column(name = "state_occurred_at", nullable = false)
    var stateOccurredAt: Instant,

    @Column(name = "authorization_version")
    var authorizationVersion: Instant? = null,

    // TODO(#596): 2026-11-01 05:00 KST 이후 실제 CD + 7일·구형 producer 종료·전체 보정 확인 후 normalize의 gap 검사와 함께 제거한다.
    // 이 임시 필드를 사용하지 않는 버전 배포 후 별도 migration으로 칼럼을 삭제한다.
    @Column(name = "authorization_state_version")
    var authorizationStateVersion: Instant? = null,
) {
    fun apply(team: Team, member: TeamMember, deleted: Boolean, requestedAt: Instant): Instant {
        require(team.id == teamId && member.team.id == teamId && member.userId == userId) {
            "Team member event state must use the same team and user ids"
        }
        normalizeAuthorizationVersion()
        val authorizationChanged = role != member.role || this.deleted != deleted
        val nextVersion = nextTeamStateOccurredAt(stateOccurredAt, requestedAt)
        role = member.role
        teamName = team.name
        nickname = member.nickname
        this.deleted = deleted
        stateOccurredAt = nextVersion
        if (authorizationChanged) authorizationVersion = nextVersion
        authorizationStateVersion = nextVersion
        return nextVersion
    }

    fun normalizeAuthorizationVersion(): Instant {
        // TODO(#596): 2026-11-01 05:00 KST 이후 실제 CD 시각 + 7일·구형 producer 중단·전체 상태 보정 완료 확인 후 NULL/gap 호환 제거.
        // A legacy writer may change membership while leaving both authorization columns untouched.
        if (authorizationVersion == null || authorizationStateVersion != stateOccurredAt) {
            authorizationVersion = stateOccurredAt
        }
        authorizationStateVersion = stateOccurredAt
        return checkNotNull(authorizationVersion)
    }

    companion object {
        fun create(team: Team, member: TeamMember, deleted: Boolean, requestedAt: Instant): TeamMemberEventState {
            val version = nextTeamStateOccurredAt(null, requestedAt)
            return TeamMemberEventState(
                teamId = team.id,
                userId = member.userId,
                role = member.role,
                teamName = team.name,
                nickname = member.nickname,
                deleted = deleted,
                stateOccurredAt = version,
                authorizationVersion = version,
                authorizationStateVersion = version,
            )
        }
    }
}
