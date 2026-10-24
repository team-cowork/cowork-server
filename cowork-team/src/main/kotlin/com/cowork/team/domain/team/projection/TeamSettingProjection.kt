package com.cowork.team.domain.team.projection

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "tb_team_setting_projections")
class TeamSettingProjection(
    @Id
    @Column(name = "team_id", nullable = false)
    val teamId: Long,

    @Column(name = "nickname_format_enforced", nullable = false)
    var nicknameFormatEnforced: Boolean = false,

    @Column(name = "nickname_format_example", columnDefinition = "TEXT")
    var nicknameFormatExample: String? = null,

    @Column(name = "source_occurred_at", nullable = false)
    var sourceOccurredAt: Instant,
) {
    fun applyUpsert(nicknameFormatEnforced: Boolean, nicknameFormatExample: String?, occurredAt: Instant) {
        this.nicknameFormatEnforced = nicknameFormatEnforced
        this.nicknameFormatExample = nicknameFormatExample
        this.sourceOccurredAt = occurredAt
    }
}
