package com.cowork.project.domain.project.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "tb_project_access_projections")
class ProjectAccessProjection(
    @Id val accessKey: String,
    val projectId: Long,
    val userId: Long,
    var canRead: Boolean,
    var canWrite: Boolean,
    @Column(nullable = false) var sourceOccurredAt: Instant,
    var canReorder: Boolean = false,
    var canManage: Boolean = false,
    @Column(nullable = false) var sourceMembershipVersion: Instant = Instant.EPOCH,
    @Column(name = "source_authorization_version") var sourceAuthorizationVersion: Instant? = null,
    // TODO(#596): 2026-11-01 05:00 KST 이후 구형 producer·재처리·롤백 지원 종료와 snapshot 보정 완료 확인 후 NULL 호환 제거.
    // 2026-10-25 05:00 KST 배포 + 7일 기준이며, 실제 배포가 늦어지면 제거일도 연기한다.
    @Column(name = "can_read_private") var canReadPrivate: Boolean? = null,
) {
    fun privateReadForSameVersion(incoming: ProjectAccessProjection): Boolean? {
        if (sourceMembershipVersion != incoming.sourceMembershipVersion ||
            sourceAuthorizationVersion != null && incoming.sourceAuthorizationVersion != null &&
            sourceAuthorizationVersion != incoming.sourceAuthorizationVersion
        ) return false
        // TODO(#596): 2026-11-01 05:00 KST 이후 위 NULL 호환 제거 조건 충족 시 명시 값 AND 병합만 유지.
        // 누락은 스냅샷으로 보정하지만 명시적 거부는 동일 버전에서 복원하지 않는다.
        return when {
            canReadPrivate == false || incoming.canReadPrivate == false -> false
            canReadPrivate == true || incoming.canReadPrivate == true -> true
            else -> null
        }
    }
}
