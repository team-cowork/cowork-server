package com.cowork.project.domain.githubBoard.event

import com.cowork.project.domain.githubBoard.entity.GithubProjectItemWriteOperation
import com.cowork.project.domain.githubBoard.repository.GithubProjectItemWriteOperationRepository
import com.cowork.project.global.outbox.OutboxWriter
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import java.util.UUID

/** Project item 쓰기 커맨드의 대상. `owner`는 설치 토큰을 고르는 조직 login이고, 아직 item이 없는 커맨드는 `itemId`가 null이다. */
data class GithubProjectItemTarget(
    val boardId: Long,
    val projectId: Long,
    val owner: String,
    val projectNodeId: String,
    val itemId: String?,
)

/**
 * 카드 쓰기를 `PENDING` 작업으로 저장하고 같은 트랜잭션의 outbox로 `github.project-item-write.command.v1`을 발행한다.
 * 사용자가 같은 카드를 여러 번 편집하는 것은 별개의 작업이므로 호출마다 새 nonce로 key를 만든다.
 */
@Component
class GithubProjectItemWriteCommandPublisher(
    private val githubProjectItemWriteOperationRepository: GithubProjectItemWriteOperationRepository,
    private val outboxWriter: OutboxWriter,
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun publish(
        target: GithubProjectItemTarget,
        commandType: GithubProjectItemWriteCommandType,
        payload: Any,
        requestedBy: Long,
        occurredAt: Instant,
    ): String {
        val idempotencyKey =
            "github-project-item-write:$commandType:${target.boardId}:${target.itemId}:$requestedBy:${UUID.randomUUID()}"
        val operationId = UUID.nameUUIDFromBytes(idempotencyKey.toByteArray(UTF_8)).toString()
        githubProjectItemWriteOperationRepository.save(
            GithubProjectItemWriteOperation(
                operationId = operationId,
                idempotencyKey = idempotencyKey,
                commandType = commandType,
                boardId = target.boardId,
                projectId = target.projectId,
                itemId = target.itemId,
                requestedBy = requestedBy,
            ),
        )
        outboxWriter.enqueue(
            GITHUB_PROJECT_ITEM_WRITE_COMMAND_TOPIC,
            target.projectNodeId,
            GithubProjectItemWriteCommand(
                operationId = operationId,
                idempotencyKey = idempotencyKey,
                commandType = commandType,
                owner = target.owner,
                projectNodeId = target.projectNodeId,
                itemId = target.itemId,
                requestedBy = requestedBy,
                occurredAt = occurredAt,
                payload = payload,
            ),
        )
        return operationId
    }
}
