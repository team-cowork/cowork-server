package com.cowork.project.domain.github.service

import com.cowork.project.domain.github.event.GITHUB_SYNC_COMMAND_TOPIC
import com.cowork.project.domain.github.event.GithubSyncCommand
import com.cowork.project.domain.github.presentation.data.response.GithubTimelineActivityResDto
import com.cowork.project.domain.github.repository.GithubIssueProjectionRepository
import com.cowork.project.domain.github.repository.GithubSyncRequestRepository
import com.cowork.project.domain.github.repository.GithubTimelineParent
import com.cowork.project.domain.github.repository.GithubTimelineRepository
import com.cowork.project.domain.github.repository.GithubTimelineSnapshot
import com.cowork.project.global.outbox.OutboxWriter
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException
import java.time.Instant
import java.util.Optional
import java.util.UUID

class QueryGithubTimelineServiceTest :
    DescribeSpec({
        val repo = GithubRepoRef("Org", "Repo")
        val subject = "org/repo#7"
        val id = UUID.randomUUID()
        val parent = GithubTimelineParent.PULL_REQUEST
        val item = GithubTimelineActivityResDto("reviewed:7", "reviewed", Instant.EPOCH)
        lateinit var access: GithubRepoAccessResolver
        lateinit var timelines: GithubTimelineRepository
        lateinit var outbox: OutboxWriter
        lateinit var requests: GithubSyncRequestRepository
        lateinit var service: QueryGithubTimelineService

        beforeEach {
            access = mockk()
            every { access.resolveForRead(1, 2, 3) } returns repo
            timelines = mockk()
            outbox = mockk(relaxed = true)
            requests = mockk(relaxed = true)
            val issues = mockk<GithubIssueProjectionRepository>()
            every { issues.existsByOwnerAndRepoAndNumberAndDeletedTrue(any(), any(), any()) } returns false
            service =
                QueryGithubTimelineService(
                    access,
                    mockk(relaxed = true),
                    mockk(relaxed = true),
                    issues,
                    timelines,
                    requests,
                    outbox,
                )
        }

        fun read(page: Int = 0, size: Int = 1, snapshotId: UUID? = null) =
            service.execute(1, 2, 3, parent, 7, page, size, snapshotId)

        it("준비된 스냅샷의 페이지 경계를 계산한다") {
            every { timelines.snapshot(subject) } returns GithubTimelineSnapshot(id, parent, 2, Instant.now())
            every { timelines.list(subject, id, any(), 1) } returns listOf(item)
            read().nextPage shouldBe 1
            read(1, snapshotId = id).nextPage shouldBe null
            verify { timelines.list(subject, id, 1L, 1) }
            verify(exactly = 0) { outbox.enqueue(any(), any(), any(), any(), any()) }
        }

        it("첫 동기화는 outbox에 해당 번호를 요청하고 503을 반환한다") {
            every { timelines.snapshot(subject) } returns null
            every { requests.findById("TIMELINE:$subject") } returns Optional.empty()
            shouldThrow<ExpectedException> { read() }.statusCode shouldBe HttpStatus.SERVICE_UNAVAILABLE
            verify {
                outbox.enqueue(
                    GITHUB_SYNC_COMMAND_TOPIC,
                    "Org/Repo",
                    match<GithubSyncCommand> {
                        it.target == "TIMELINE" &&
                            it.number == 7
                    },
                )
            }
        }

        it("오래된 이력은 마지막 동기화 시각과 함께 반환하고 갱신을 요청한다") {
            every { timelines.snapshot(subject) } returns GithubTimelineSnapshot(id, parent, 1, Instant.EPOCH)
            every { timelines.list(subject, id, 0L, 1) } returns listOf(item)
            every { requests.findById("TIMELINE:$subject") } returns Optional.empty()
            val page = read()
            page.syncedAt shouldBe Instant.EPOCH
            page.items shouldBe listOf(item)
            verify(exactly = 1) { outbox.enqueue(GITHUB_SYNC_COMMAND_TOPIC, any(), any(), any(), any()) }
        }

        it("활동이 없는 대상은 빈 페이지로 반환한다") {
            every { timelines.snapshot(subject) } returns GithubTimelineSnapshot(id, parent, 0, Instant.now())
            every { timelines.list(subject, id, 0L, 1) } returns emptyList()
            read().items shouldBe emptyList()
            read().nextPage shouldBe null
        }

        it("종류가 다르거나 대상이 없으면 404를 반환한다") {
            for (other in listOf(GithubTimelineParent.ISSUE, GithubTimelineParent.MISSING)) {
                every { timelines.snapshot(subject) } returns GithubTimelineSnapshot(id, other, 0, Instant.now())
                shouldThrow<ExpectedException> { read() }.statusCode shouldBe HttpStatus.NOT_FOUND
            }
        }

        it("이전 스냅샷으로 다음 페이지를 요청하면 409를 반환한다") {
            every { timelines.snapshot(subject) } returns GithubTimelineSnapshot(id, parent, 2, Instant.now())
            shouldThrow<ExpectedException> { read(1, snapshotId = UUID.randomUUID()) }.statusCode shouldBe
                HttpStatus.CONFLICT
        }

        it("잘못된 페이지 범위는 400을 반환한다") {
            for ((page, size) in listOf(-1 to 1, 0 to 0, 0 to 101)) {
                shouldThrow<ExpectedException> { read(page, size) }.statusCode shouldBe HttpStatus.BAD_REQUEST
            }
        }

        it("프로젝트 읽기 권한이 없으면 이력 조회와 동기화를 시작하지 않는다") {
            every { access.resolveForRead(1, 2, 3) } throws ExpectedException("권한이 없습니다.", HttpStatus.FORBIDDEN)
            shouldThrow<ExpectedException> { read() }.statusCode shouldBe HttpStatus.FORBIDDEN
            verify(exactly = 0) { timelines.snapshot(any()) }
            verify(exactly = 0) { outbox.enqueue(any(), any(), any(), any(), any()) }
        }
    })
