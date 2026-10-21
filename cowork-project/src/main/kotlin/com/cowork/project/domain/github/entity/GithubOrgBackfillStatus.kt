package com.cowork.project.domain.github.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * `cowork-github`가 설치 직후 수행하는 전체 백필이 완료됐는지 추적한다. 백필이 끝난 org만
 * `GithubRepoProjectionRepository`를 신뢰해 조회하고, 그 전에는 `GithubAppClient`로 직접 조회한다
 * (repo 목록이 비어 있는 것과 "아직 백필 전"을 구분할 방법이 이것 말고는 없다).
 */
@Entity
@Table(name = "tb_github_org_backfill_statuses")
class GithubOrgBackfillStatus(
    @Id
    @Column(name = "org_login", nullable = false, length = 100)
    val orgLogin: String,

    @Column(name = "completed_at", nullable = false)
    var completedAt: Instant,
)
