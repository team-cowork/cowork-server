package com.cowork.project.domain.github.event

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.time.Instant

const val GITHUB_ISSUE_WRITE_COMMAND_TOPIC = "github-app.issue-write.command"
const val GITHUB_ISSUE_WRITE_RESULT_TOPIC = "github-app.issue-write.result"

enum class GithubIssueWriteCommandType { REPLACE_LABELS, CREATE_COMMENT, UPDATE_COMMENT, DELETE_COMMENT }

// ---- Command (cowork-project가 발행하고 cowork-github-app이 소비) ----

data class GithubReplaceLabelsPayload(val issueNumber: Int, val labels: List<String>)

data class GithubCreateCommentPayload(val issueNumber: Int, val body: String, val requesterGithubUsername: String)

data class GithubUpdateCommentPayload(val commentId: Long, val body: String)

data class GithubDeleteCommentPayload(val commentId: Long)

/**
 * 이슈 라벨 전체 교체·댓글 생성/수정/삭제를 cowork-github-app에 위임하는 Kafka 커맨드 envelope.
 *
 * `payload`는 [commandType]에 따라 [GithubReplaceLabelsPayload] / [GithubCreateCommentPayload] /
 * [GithubUpdateCommentPayload] / [GithubDeleteCommentPayload] 중 하나가 중첩된다. Kafka 메시지 키는
 * `operationId`가 아니라 `"$owner/$repo"`를 사용해 같은 레포에 대한 쓰기 작업의 순서를 파티션 내에서 보장한다
 * ([com.cowork.project.domain.github.event.GithubActionCommandPublisher.publishIssueCreate]와 동일한 관례).
 */
data class GithubIssueWriteCommand(
    val schemaVersion: Int = 1,
    val operationId: String,
    val idempotencyKey: String,
    val commandType: GithubIssueWriteCommandType,
    val owner: String,
    val repo: String,
    val requestedBy: Long,
    val occurredAt: Instant,
    val payload: Any,
)

// ---- Result (cowork-github-app이 발행하고 cowork-project가 소비) ----

@JsonIgnoreProperties(ignoreUnknown = true)
data class GithubIssueWriteLabelResult(val name: String = "", val color: String = "")

@JsonIgnoreProperties(ignoreUnknown = true)
data class GithubIssueWriteLabelsResultPayload(val labels: List<GithubIssueWriteLabelResult> = emptyList())

@JsonIgnoreProperties(ignoreUnknown = true)
data class GithubIssueWriteCommentResultPayload(
    val id: Long = 0,
    val author: String = "",
    val body: String = "",
    val htmlUrl: String = "",
    val createdAt: String = "",
    val updatedAt: String = "",
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class GithubIssueWriteError(val code: String = "", val message: String = "")

/**
 * [GithubIssueWriteCommand] 처리 결과. `result`는 SUCCEEDED일 때만 채워지고 [commandType]에 따라
 * 모양이 달라지므로(라벨 배열 vs 댓글 필드) 느슨한 `Map`으로 받은 뒤 필요한 시점에
 * [GithubIssueWriteLabelsResultPayload]/[GithubIssueWriteCommentResultPayload]로 변환한다.
 * `DELETE_COMMENT` 성공 시 `result`는 아예 없다. cowork-github-app은 `idempotencyKey`로 중복 제거를
 * 하지 않고 그대로 echo만 하므로, 재전달된 result를 반영할 때도 이 값이 우리가 보낸 값과 일치하는지
 * 검증해야 한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class GithubIssueWriteResult(
    val schemaVersion: Int,
    val operationId: String,
    val idempotencyKey: String,
    val commandType: String,
    val status: String,
    val result: Map<String, Any?>? = null,
    val error: GithubIssueWriteError? = null,
    val occurredAt: Instant? = null,
)
