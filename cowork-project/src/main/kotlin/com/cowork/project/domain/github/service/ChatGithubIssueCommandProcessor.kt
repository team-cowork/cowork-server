package com.cowork.project.domain.github.service

import com.cowork.project.domain.github.entity.ChatGithubIssueCreateOperation
import com.cowork.project.domain.github.event.CHAT_GITHUB_ISSUE_RESULT_TOPIC
import com.cowork.project.domain.github.event.ChatGithubIssueCreateCommand
import com.cowork.project.domain.github.event.ChatGithubIssueCreateError
import com.cowork.project.domain.github.event.ChatGithubIssueCreateResult
import com.cowork.project.domain.github.event.GithubActionCommandPublisher
import com.cowork.project.domain.github.event.GithubIssueCreateCommand
import com.cowork.project.domain.github.repository.ChatGithubIssueCreateOperationRepository
import com.cowork.project.domain.github.repository.ProjectGithubRepoRepository
import com.cowork.project.domain.project.service.ProjectAccessGuard
import com.cowork.project.global.outbox.OutboxWriter
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException
import java.time.Instant

/**
 * cowork-chat 슬래시 커맨드로 시작한 GitHub 이슈 생성 요청의 권한을 검증한다.
 *
 * `CreateGithubIssueServiceImpl`(REST 경유)와 동일하게 [ProjectAccessGuard.requireProjectModifier]로
 * 프로젝트 수정 권한(프로젝트 OWNER·EDITOR 또는 팀 OWNER·ADMIN)을 검증하며,
 * chat이 이미 확인한 채널-팀 소속을 프로젝트 기준으로 다시 한번 검증한다.
 *
 * 커맨드당 정확히 하나의 최종 결정(ACCEPTED/REJECTED)만 내리고, 그 결정과 결과 발행을
 * 하나의 트랜잭션으로 묶는다. 같은 `operationId`가 재전달되면 재처리 없이 무시한다.
 */
@Service
class ChatGithubIssueCommandProcessor(
    private val operationRepository: ChatGithubIssueCreateOperationRepository,
    private val projectAccessGuard: ProjectAccessGuard,
    private val projectGithubRepoRepository: ProjectGithubRepoRepository,
    private val commandPublisher: GithubActionCommandPublisher,
    private val outboxWriter: OutboxWriter,
) {
    private val log = LoggerFactory.getLogger(ChatGithubIssueCommandProcessor::class.java)

    @Transactional
    fun process(command: ChatGithubIssueCreateCommand) {
        if (operationRepository.existsById(command.operationId)) {
            log.info("Chat GitHub issue command already processed operationId={}", command.operationId)
            return
        }

        val decision = decide(command)
        val operation = ChatGithubIssueCreateOperation(
            operationId = command.operationId,
            idempotencyKey = command.idempotencyKey,
            projectId = command.projectId,
            requesterId = command.requesterId,
            channelId = command.channelId,
            teamId = command.teamId,
        )

        when (decision) {
            is Decision.Accepted -> {
                operation.accept()
                commandPublisher.publishIssueCreate(
                    GithubIssueCreateCommand(
                        owner = decision.repo.owner,
                        repo = decision.repo.repo,
                        title = command.title,
                        body = command.body,
                        labels = emptyList(),
                        channelId = command.channelId,
                        teamId = command.teamId,
                        requesterId = command.requesterId,
                    ),
                )
            }
            is Decision.Rejected -> operation.reject(decision.code, decision.message)
        }
        operationRepository.save(operation)

        outboxWriter.enqueue(
            CHAT_GITHUB_ISSUE_RESULT_TOPIC,
            command.channelId.toString(),
            ChatGithubIssueCreateResult(
                operationId = command.operationId,
                idempotencyKey = command.idempotencyKey,
                channelId = command.channelId,
                teamId = command.teamId,
                projectId = command.projectId,
                requesterId = command.requesterId,
                status = decision.status,
                error = (decision as? Decision.Rejected)?.let { ChatGithubIssueCreateError(it.code, it.message) },
                occurredAt = Instant.now(),
            ),
        )
    }

    private fun decide(command: ChatGithubIssueCreateCommand): Decision {
        val project = try {
            projectAccessGuard.findProjectOrThrow(command.projectId)
        } catch (ex: ExpectedException) {
            return Decision.Rejected(ex.statusCode.name, ex.message ?: "프로젝트를 찾을 수 없습니다.")
        }

        if (project.teamId != command.teamId) {
            return Decision.Rejected(TEAM_SCOPE_MISMATCH, "해당 프로젝트는 이 채널의 팀에 속하지 않습니다")
        }

        val repos = projectGithubRepoRepository.findAllByProjectId(command.projectId)
        if (repos.isEmpty()) {
            return Decision.Rejected(GITHUB_REPO_NOT_FOUND, "프로젝트 GitHub 레포지토리 정보를 찾을 수 없습니다")
        }
        if (repos.size > 1) {
            return Decision.Rejected(GITHUB_REPO_AMBIGUOUS, "프로젝트에 연결된 저장소가 여러 개여서 이슈 대상을 결정할 수 없습니다")
        }

        try {
            projectAccessGuard.requireProjectModifier(project, command.requesterId)
        } catch (ex: ExpectedException) {
            return Decision.Rejected(ex.statusCode.name, ex.message ?: "프로젝트 수정 권한이 없습니다.")
        }

        val repo = GithubRepoUrlParser.parse(repos[0].githubRepoUrl)
            ?: return Decision.Rejected(GITHUB_REPO_URL_INVALID, "연결된 GitHub 레포지토리 URL이 올바르지 않습니다.")

        return Decision.Accepted(repo)
    }

    private sealed interface Decision {
        val status: String

        data class Accepted(val repo: GithubRepoRef) : Decision {
            override val status = "ACCEPTED"
        }

        data class Rejected(val code: String, val message: String) : Decision {
            override val status = "REJECTED"
        }
    }

    private companion object {
        const val TEAM_SCOPE_MISMATCH = "TEAM_SCOPE_MISMATCH"
        const val GITHUB_REPO_NOT_FOUND = "GITHUB_REPO_NOT_FOUND"
        const val GITHUB_REPO_AMBIGUOUS = "GITHUB_REPO_AMBIGUOUS"
        const val GITHUB_REPO_URL_INVALID = "GITHUB_REPO_URL_INVALID"
    }
}
