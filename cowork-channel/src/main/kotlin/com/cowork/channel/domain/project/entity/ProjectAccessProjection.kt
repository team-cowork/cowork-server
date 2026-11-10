package com.cowork.channel.domain.project.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/** `preference.project-access.state.v1`로 받은 프로젝트별 사용자 권한 중 채널 서비스가 쓰는 값. */
@Entity
@Table(name = "tb_project_access_projections")
class ProjectAccessProjection(
    @Id
    @Column(name = "access_key", nullable = false, length = 50)
    val accessKey: String,

    @Column(name = "project_id", nullable = false)
    val projectId: Long,

    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Column(name = "can_read", nullable = false)
    var canRead: Boolean,

    @Column(name = "can_channel_archive", nullable = false)
    var canChannelArchive: Boolean,

    @Column(name = "source_occurred_at", nullable = false)
    var sourceOccurredAt: Instant,

    @Column(name = "source_membership_version", nullable = false)
    var sourceMembershipVersion: Instant,

    @Column(name = "source_authorization_version")
    var sourceAuthorizationVersion: Instant?,
) {
    /**
     * 새 버전은 그대로 덮어쓴다. 같은 버전은 replay된 snapshot이므로 두 값이 모두 허용할 때만 권한을 유지하고,
     * 서로 다른 같은 버전 기록이 권한을 되살리지 못하게 한다. 값이 바뀌면 true를 반환한다.
     */
    fun merge(incoming: ProjectAccessProjection): Boolean {
        if (incoming.sourceOccurredAt.isAfter(sourceOccurredAt)) {
            canRead = incoming.canRead
            canChannelArchive = incoming.canChannelArchive
            sourceOccurredAt = incoming.sourceOccurredAt
            sourceMembershipVersion = incoming.sourceMembershipVersion
            sourceAuthorizationVersion = incoming.sourceAuthorizationVersion
            return true
        }
        if (incoming.sourceOccurredAt != sourceOccurredAt) return false
        val sameAuthorization = sourceMembershipVersion == incoming.sourceMembershipVersion &&
            sourceAuthorizationVersion != null &&
            sourceAuthorizationVersion == incoming.sourceAuthorizationVersion
        val read = sameAuthorization && canRead && incoming.canRead
        val channelArchive = canChannelArchive && incoming.canChannelArchive
        if (read == canRead && channelArchive == canChannelArchive) return false
        canRead = read
        canChannelArchive = channelArchive
        return true
    }

    /** 팀 멤버십의 현재 권한 버전으로 계산된 값일 때만 채널 보관을 허용한다. */
    fun allowsChannelArchive(currentAuthorizationVersion: Instant?): Boolean = canRead && canChannelArchive &&
        currentAuthorizationVersion != null && sourceAuthorizationVersion == currentAuthorizationVersion

    companion object {
        fun key(projectId: Long, userId: Long): String = "$projectId:$userId"
    }
}
