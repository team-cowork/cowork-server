package com.cowork.project.domain.githubPreference.event

import com.cowork.project.global.outbox.OutboxWriter
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import java.util.UUID

@Component
class GithubRepoSettingCommandPublisher(private val outboxWriter: OutboxWriter) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun publishDelete(repoId: Long, requestedBy: Long?, occurredAt: Instant) {
        require(repoId > 0) { "repoId must be positive" }
        require(requestedBy == null || requestedBy > 0) { "requestedBy must be positive when present" }
        val idempotencyKey = deleteIdempotencyKey(repoId)
        outboxWriter.enqueue(
            GITHUB_REPO_SETTING_COMMAND_TOPIC,
            repoId.toString(),
            DeleteGithubRepoSettingCommand(
                operationId = UUID.nameUUIDFromBytes(idempotencyKey.toByteArray(UTF_8)).toString(),
                idempotencyKey = idempotencyKey,
                repoId = repoId,
                requestedBy = requestedBy,
                occurredAt = occurredAt,
            ),
        )
    }

    private fun deleteIdempotencyKey(repoId: Long) = "github-repo-setting-delete:$repoId"
}
