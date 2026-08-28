package com.cowork.team.domain.teamRole.projection

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "tb_team_role_member_tombstones")
class TeamRoleMemberTombstone(
    @Id
    @Column(name = "projection_key", nullable = false, length = 80)
    val projectionKey: String,

    @Column(name = "team_id", nullable = false)
    val teamId: Long,

    @Column(name = "account_id", nullable = false)
    val accountId: Long,

    @Column(name = "source_occurred_at", nullable = false)
    var sourceOccurredAt: Instant,
) {
    companion object {
        fun key(teamId: Long, accountId: Long): String = "$teamId:$accountId"
    }
}
