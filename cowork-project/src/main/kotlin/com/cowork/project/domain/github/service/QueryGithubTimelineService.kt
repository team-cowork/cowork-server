package com.cowork.project.domain.github.service

import com.cowork.project.domain.github.entity.GithubSyncStream
import com.cowork.project.domain.github.event.GITHUB_SYNC_COMMAND_TOPIC
import com.cowork.project.domain.github.event.GithubSyncCommand
import com.cowork.project.domain.github.event.key
import com.cowork.project.domain.github.presentation.data.response.GithubTimelineResDto
import com.cowork.project.domain.github.repository.GithubIssueProjectionRepository
import com.cowork.project.domain.github.repository.GithubSyncRequestRepository
import com.cowork.project.domain.github.repository.GithubTimelineParent
import com.cowork.project.domain.github.repository.GithubTimelineRepository
import com.cowork.project.global.outbox.OutboxWriter
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import team.themoment.sdk.exception.ExpectedException
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Service
class QueryGithubTimelineService(
    private val githubRepoAccessResolver: GithubRepoAccessResolver,
    private val githubTimelineReadiness: GithubTimelineReadiness,
    private val githubSyncMarkers: GithubSyncMarkers,
    private val githubIssueProjectionRepository: GithubIssueProjectionRepository,
    private val githubTimelineRepository: GithubTimelineRepository,
    private val githubSyncRequestRepository: GithubSyncRequestRepository,
    private val outboxWriter: OutboxWriter,
) {
    // 503 또는 404로 응답해도 오래된 이력을 다시 조회하는 outbox 명령은 커밋한다.
    @Transactional(isolation = Isolation.REPEATABLE_READ, noRollbackFor = [ExpectedException::class])
    fun execute(
        userId: Long,
        projectId: Long,
        repoId: Long,
        parentType: GithubTimelineParent,
        number: Int,
        page: Int,
        size: Int,
        snapshotId: UUID?,
    ): GithubTimelineResDto {
        if (number < 1 || page < 0 || size !in 1..100) {
            throw ExpectedException("번호 또는 페이지 범위가 올바르지 않습니다.", HttpStatus.BAD_REQUEST)
        }
        if (page > 0 && snapshotId == null) {
            throw ExpectedException("다음 페이지를 조회하려면 스냅샷 ID를 지정하십시오.", HttpStatus.BAD_REQUEST)
        }
        val repo = githubRepoAccessResolver.resolveForRead(userId, projectId, repoId)
        githubTimelineReadiness.requireReady()
        val stream = when (parentType) {
            GithubTimelineParent.ISSUE -> GithubSyncStream.ISSUE
            GithubTimelineParent.PULL_REQUEST -> GithubSyncStream.PULL_REQUEST
            GithubTimelineParent.MISSING -> error("Invalid timeline route")
        }
        githubSyncMarkers.requireSynced(stream, repo.owner, repo.repo)
        val deleted = githubIssueProjectionRepository.existsByOwnerAndRepoAndNumberAndDeletedTrue(
            repo.owner,
            repo.repo,
            number,
        )
        if (deleted) {
            throw ExpectedException("GitHub에서 대상을 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
        }
        val subject = "${repo.owner.lowercase()}/${repo.repo.lowercase()}#$number"
        val snapshot = githubTimelineRepository.snapshot(subject)
        val now = Instant.now()
        if (snapshot == null || snapshot.completedAt.isBefore(now.minus(REFRESH_INTERVAL))) {
            requestRefresh(repo, number, subject, now)
        }
        if (snapshot == null) {
            throw ExpectedException("활동 이력을 동기화하고 있습니다. 잠시 후 다시 조회하십시오.", HttpStatus.SERVICE_UNAVAILABLE)
        }
        if (snapshot.parent != parentType) {
            throw ExpectedException("GitHub에서 대상을 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
        }
        if (snapshotId != null && snapshotId != snapshot.id) {
            throw ExpectedException("활동 이력이 갱신되었습니다. 첫 페이지부터 다시 조회하십시오.", HttpStatus.CONFLICT)
        }
        val offset = page.toLong() * size
        val items = githubTimelineRepository.list(subject, snapshot.id, offset, size)
        val nextPage = if (offset + size < snapshot.count) page + 1 else null
        return GithubTimelineResDto(items, snapshot.id, snapshot.completedAt, nextPage)
    }

    private fun requestRefresh(repo: GithubRepoRef, number: Int, subject: String, now: Instant) {
        val id = "TIMELINE:$subject"
        val last = githubSyncRequestRepository.findById(id).orElse(null)
        if (last != null && last.requestedAt.isAfter(now.minus(RETRY_INTERVAL))) return
        githubSyncRequestRepository.upsert(id, now)
        val command =
            GithubSyncCommand(
                target = "TIMELINE",
                owner = repo.owner,
                repo = repo.repo,
                installationId = null,
                requestedAt = now,
                number = number,
            )
        outboxWriter.enqueue(GITHUB_SYNC_COMMAND_TOPIC, command.key, command)
    }

    private companion object {
        val REFRESH_INTERVAL: Duration = Duration.ofMinutes(5)
        val RETRY_INTERVAL: Duration = Duration.ofMinutes(1)
    }
}
