package com.cowork.project.domain.github.event

import java.time.Instant

const val CHAT_GITHUB_ISSUE_COMMAND_TOPIC = "project.chat-github-issue.command"
const val CHAT_GITHUB_ISSUE_RESULT_TOPIC = "project.chat-github-issue.result"

/**
 * cowork-chat 슬래시 커맨드로 시작한 GitHub 이슈 생성 요청.
 *
 * cowork-chat이 발행하고 cowork-project가 소비한다. project는 프로젝트 수정 권한
 * (프로젝트 OWNER·EDITOR 또는 팀 OWNER·ADMIN)과 프로젝트-채널 팀 경계를 검증한 뒤,
 * 통과하면 `github.issue.create`로 실제 이슈 생성 커맨드를 발행한다.
 * 검증 결과는 [ChatGithubIssueCreateResult]로 역방향 토픽에 응답된다.
 */
data class ChatGithubIssueCreateCommand(
    val schemaVersion: Int = 1,
    val operationId: String,
    val idempotencyKey: String,
    val projectId: Long,
    val channelId: Long,
    val teamId: Long,
    val requesterId: Long,
    val title: String,
    val body: String?,
    val occurredAt: Instant,
)

data class ChatGithubIssueCreateError(val code: String, val message: String)

/**
 * [ChatGithubIssueCreateCommand] 처리 결과(권한 검증 수락/거부)를 전달하는 이벤트.
 *
 * cowork-project가 발행하고 cowork-chat이 소비한다. `status`가 `REJECTED`일 때만
 * `error`가 채워지며, chat은 이 경우에만 거부 시스템 메시지를 렌더링한다.
 * `ACCEPTED`는 권한 검증 통과만을 의미하고, 실제 이슈 생성 성공/실패는
 * 기존 `github.issue.result` 토픽으로 별도 전달된다.
 */
data class ChatGithubIssueCreateResult(
    val schemaVersion: Int = 1,
    val operationId: String,
    val idempotencyKey: String,
    val channelId: Long,
    val teamId: Long,
    val projectId: Long,
    val requesterId: Long,
    val status: String,
    val error: ChatGithubIssueCreateError? = null,
    val occurredAt: Instant,
)
