package com.cowork.channel.domain.channelRolePolicy.access

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Converter
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.Instant

/** `preference.channel-access.state.v1`로 받은 채널별 사용자 권한 판정 결과. */
@Entity
@Table(
    name = "tb_channel_access_projections",
    indexes = [Index(name = "idx_tb_channel_access_projections_channel_id", columnList = "channel_id")],
)
class ChannelAccessProjection(
    @Id
    @Column(name = "access_key", nullable = false, length = 50)
    val accessKey: String,

    @Column(name = "channel_id", nullable = false)
    val channelId: Long,

    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Column(name = "team_id", nullable = false)
    var teamId: Long,

    @Convert(converter = PermissionSetConverter::class)
    @Column(name = "allowed_permissions", nullable = false, length = 500)
    var allowedPermissions: Set<String>,

    @Column(name = "can_read_private", nullable = false)
    var canReadPrivate: Boolean,

    @Column(name = "source_occurred_at", nullable = false)
    var sourceOccurredAt: Instant,

    @Column(name = "source_membership_version", nullable = false)
    var sourceMembershipVersion: Instant,

    @Column(name = "source_authorization_version")
    var sourceAuthorizationVersion: Instant?,
) {
    /**
     * 새 버전은 그대로 덮어쓴다. 같은 버전은 replay된 snapshot이므로 두 기록이 모두 허용한 권한만 남기고,
     * 서로 다른 같은 버전 기록이 권한을 되살리지 못하게 한다. 값이 바뀌면 true를 반환한다.
     */
    fun merge(incoming: ChannelAccessProjection): Boolean {
        if (incoming.sourceOccurredAt.isAfter(sourceOccurredAt)) {
            teamId = incoming.teamId
            allowedPermissions = incoming.allowedPermissions
            canReadPrivate = incoming.canReadPrivate
            sourceOccurredAt = incoming.sourceOccurredAt
            sourceMembershipVersion = incoming.sourceMembershipVersion
            sourceAuthorizationVersion = incoming.sourceAuthorizationVersion
            return true
        }
        if (incoming.sourceOccurredAt != sourceOccurredAt) return false
        val sameSource = teamId == incoming.teamId &&
            sourceMembershipVersion == incoming.sourceMembershipVersion &&
            sourceAuthorizationVersion == incoming.sourceAuthorizationVersion
        val permissions = if (sameSource) allowedPermissions intersect incoming.allowedPermissions else emptySet()
        val readPrivate = sameSource && canReadPrivate && incoming.canReadPrivate
        if (permissions == allowedPermissions && readPrivate == canReadPrivate) return false
        allowedPermissions = permissions
        canReadPrivate = readPrivate
        return true
    }

    /**
     * 팀 멤버십의 현재 인가 version으로 계산된 판정만 쓴다. 멤버십 변경이 판정 결과보다 먼저 도착하면
     * Preference가 결과를 다시 발행할 때까지 거부한다.
     */
    fun isCurrentFor(teamId: Long, currentAuthorizationVersion: Instant?): Boolean = this.teamId == teamId &&
        currentAuthorizationVersion != null && sourceAuthorizationVersion == currentAuthorizationVersion

    companion object {
        fun key(channelId: Long, userId: Long): String = "$channelId:$userId"
    }
}

@Converter
class PermissionSetConverter : AttributeConverter<Set<String>, String> {
    override fun convertToDatabaseColumn(attribute: Set<String>): String = attribute.sorted().joinToString(",")

    override fun convertToEntityAttribute(dbData: String): Set<String> =
        dbData.split(',').filterTo(linkedSetOf()) { it.isNotEmpty() }
}
