package com.cowork.project.domain.github.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.Locale

/** 동기화 완료 마커가 실리는 state 토픽. */
enum class GithubSyncStream { REPO, ISSUE, PULL_REQUEST, LABEL, ISSUE_COMMENT }

/**
 * `cowork-github`가 동기화 대상의 state 레코드를 모두 보낸 뒤 같은 state 토픽에 남기는 완료 마커의 기록.
 * 마커가 있는 토픽·대상(repo, 또는 org의 repo 목록)만 projection을 완전한 목록으로 믿는다.
 */
@Entity
@Table(name = "tb_github_sync_markers")
class GithubSyncMarker(
    @Id
    @Column(nullable = false, length = 300)
    val id: String,

    @Column(name = "completed_at", nullable = false)
    var completedAt: Instant,
) {
    companion object {
        /**
         * `repo`가 null이면 org 단위(repo 목록) 마커다. GitHub은 owner/repo 대소문자를 구분하지 않으므로
         * `project.github-repo.event`와 같은 규칙으로 소문자로 맞춘다.
         */
        fun id(stream: GithubSyncStream, owner: String, repo: String?): String {
            val scope = if (repo == null) owner else "$owner/$repo"
            return "$stream:${scope.lowercase(Locale.ROOT)}"
        }
    }
}
