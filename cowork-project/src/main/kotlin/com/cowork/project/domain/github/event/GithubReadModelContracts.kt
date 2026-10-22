package com.cowork.project.domain.github.event

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.time.Instant

const val GITHUB_REPO_STATE_TOPIC = "github.repo.state.v1"
const val GITHUB_ISSUE_STATE_TOPIC = "github.issue.state.v1"
const val GITHUB_PULL_REQUEST_STATE_TOPIC = "github.pull-request.state.v1"
const val GITHUB_LABEL_STATE_TOPIC = "github.label.state.v1"
const val GITHUB_ISSUE_COMMENT_STATE_TOPIC = "github.issue-comment.state.v1"

/**
 * cowork-github가 webhook/backfill로 발행하는 repo read-model state.
 * `GithubAppClient.listOrgRepos`를 대체하는 projection의 소스 이벤트.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class GithubRepoStateEvent(
    val installationId: Long = 0,
    val owner: String = "",
    val repo: String = "",
    val fullName: String = "",
    val private: Boolean = false,
    val htmlUrl: String = "",
    val deleted: Boolean = false,
    val sourceOccurredAt: Instant = Instant.EPOCH,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class GithubIssueStateEvent(
    val owner: String = "",
    val repo: String = "",
    val number: Int = 0,
    val title: String = "",
    val author: String = "",
    val state: String = "",
    val htmlUrl: String = "",
    val labels: List<String> = emptyList(),
    val createdAt: String = "",
    val updatedAt: String = "",
    val deleted: Boolean = false,
    val sourceOccurredAt: Instant = Instant.EPOCH,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class GithubPullRequestFileState(
    val filename: String = "",
    val status: String = "",
    val additions: Int = 0,
    val deletions: Int = 0,
    val patch: String? = null,
)

/**
 * `files`는 opened/synchronize/reopened 액션에서만 채워진다(webhook payload에 PR 변경 파일
 * 목록이 없어, 해당 액션에서만 cowork-github가 GitHub API로 다시 조회해 포함시킨다). 이 필드가
 * 없는 이벤트를 받으면 기존 저장된 파일 목록을 그대로 유지해야 한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class GithubPullRequestStateEvent(
    val owner: String = "",
    val repo: String = "",
    val number: Int = 0,
    val title: String = "",
    val body: String? = null,
    val author: String = "",
    val state: String = "",
    val draft: Boolean = false,
    val merged: Boolean = false,
    val htmlUrl: String = "",
    val labels: List<String> = emptyList(),
    val headRef: String = "",
    val baseRef: String = "",
    val createdAt: String = "",
    val updatedAt: String = "",
    val sourceOccurredAt: Instant = Instant.EPOCH,
    val files: List<GithubPullRequestFileState>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class GithubLabelStateEvent(
    val owner: String = "",
    val repo: String = "",
    val name: String = "",
    val color: String = "",
    val deleted: Boolean = false,
    val sourceOccurredAt: Instant = Instant.EPOCH,
)

/**
 * 각 state 토픽에 실리는 동기화 완료 마커. `repo`가 null이면 org 단위(repo 목록) 마커다.
 * 엔티티 state 레코드와는 `recordType`으로 구분한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class GithubSyncCompletedMarker(
    val recordType: String = "",
    val owner: String = "",
    val repo: String? = null,
    val completedAt: Instant = Instant.EPOCH,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class GithubIssueCommentStateEvent(
    val owner: String = "",
    val repo: String = "",
    val commentId: Long = 0,
    val parentType: String = "",
    val parentNumber: Int = 0,
    val author: String = "",
    val body: String = "",
    val htmlUrl: String = "",
    val createdAt: String = "",
    val updatedAt: String = "",
    val deleted: Boolean = false,
    val sourceOccurredAt: Instant = Instant.EPOCH,
)
