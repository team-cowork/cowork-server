package com.cowork.project.domain.github.service

import com.cowork.project.domain.github.entity.GithubRepoProjection
import com.cowork.project.domain.github.repository.GithubRepoProjectionRepository
import com.cowork.project.domain.github.repository.GithubSyncMarkerRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException
import java.time.Instant

class GithubRepoReaderTest :
    DescribeSpec({

        lateinit var markerRepository: GithubSyncMarkerRepository
        lateinit var repoProjectionRepository: GithubRepoProjectionRepository
        lateinit var reader: GithubRepoReader

        beforeEach {
            markerRepository = mockk()
            repoProjectionRepository = mockk()
            reader = GithubRepoReader(GithubSyncMarkers(markerRepository), repoProjectionRepository)
        }

        describe("GithubRepoReader 클래스의") {
            describe("listOrgRepos 메서드는") {
                context("org의 repo 목록 동기화 마커가 없는 경우") {
                    it("503을 던지고 projection을 읽지 않는다") {
                        every { markerRepository.existsById("REPO:my-org") } returns false

                        val ex = shouldThrow<ExpectedException> { reader.listOrgRepos("my-org") }

                        ex.statusCode shouldBe HttpStatus.SERVICE_UNAVAILABLE
                        verify(exactly = 0) { repoProjectionRepository.findByOwnerAndDeletedFalse(any()) }
                    }
                }

                context("org의 repo 목록 동기화 마커가 있는 경우") {
                    it("로컬 projection에서 조회한다") {
                        every { markerRepository.existsById("REPO:my-org") } returns true
                        every { repoProjectionRepository.findByOwnerAndDeletedFalse("my-org") } returns listOf(
                            GithubRepoProjection(
                                id = "my-org/my-repo",
                                installationId = 1L,
                                owner = "my-org",
                                repo = "my-repo",
                                fullName = "my-org/my-repo",
                                private = true,
                                htmlUrl = "https://github.com/my-org/my-repo",
                                sourceOccurredAt = Instant.EPOCH,
                            ),
                        )

                        val result = reader.listOrgRepos("my-org")

                        result.map { it.fullName } shouldBe listOf("my-org/my-repo")
                    }
                }
            }
        }
    })
