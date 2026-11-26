package com.cowork.team.domain.team.service

import com.cowork.team.domain.team.entity.Team
import com.cowork.team.domain.team.entity.TeamIconUpload
import com.cowork.team.domain.team.repository.TeamIconUploadRepository
import com.cowork.team.domain.team.repository.TeamRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException

class TeamIconOwnershipManagerTest :
    DescribeSpec({
        val key = "team-icons/12345678-1234-1234-1234-123456789012.png"
        val uploadKey = "team-icon-uploads/7/12345678-1234-1234-1234-123456789012.png"
        val iconUrl = "https://storage.example.com/bucket/$key"
        lateinit var uploads: TeamIconUploadRepository
        lateinit var storage: TeamIconStorage
        lateinit var teams: TeamRepository
        lateinit var service: TeamIconOwnershipManager
        lateinit var upload: TeamIconUpload

        fun team(id: Long = 10L, icon: String? = null) = Team(id, "team", null, icon, 7L)

        beforeEach {
            uploads = mockk()
            storage = mockk(relaxed = true)
            teams = mockk()
            upload = TeamIconUpload(key, uploadKey, 7L, "image/png", teamId = 10L)
            every { storage.extractObjectKey(iconUrl) } returns key
            every { storage.managedObjectKeyOrNull(iconUrl) } returns key
            every { storage.buildPublicUrl(key) } returns iconUrl
            every { uploads.findByObjectKeyForUpdate(key) } returns upload
            every { uploads.findByUploadKeyForUpdate(uploadKey) } returns upload
            every { teams.existsById(10L) } returns true
            service = TeamIconOwnershipManager(uploads, storage, teams)
        }

        describe("TeamIconOwnershipManager 클래스의 issue 메서드는") {
            context("팀 관리자가 아이콘 업로드 URL을 요청하면") {
                it("발급 대상 팀을 업로드 기록에 저장한다") {
                    val saved = slot<TeamIconUpload>()
                    every { storage.buildObjectKey("image/png") } returns key
                    every { uploads.save(capture(saved)) } answers { firstArg() }
                    service.issue(7L, 10L, "image/png")
                    saved.captured.teamId shouldBe 10L
                    saved.captured.uploadedBy shouldBe 7L
                    saved.captured.uploadKey shouldBe uploadKey
                }
            }
        }

        describe("아이콘 업로드 확인은") {
            it("다른 사용자가 발급한 업로드를 거부한다") {
                shouldThrow<ExpectedException> { service.confirm(8L, uploadKey) }.statusCode shouldBe
                    HttpStatus.FORBIDDEN
                verify(exactly = 0) { storage.finalizeUpload(any(), any(), any()) }
            }
            it("발급하지 않은 경로를 거부한다") {
                every { uploads.findByUploadKeyForUpdate(uploadKey) } returns null
                shouldThrow<ExpectedException> { service.confirm(7L, uploadKey) }.statusCode shouldBe
                    HttpStatus.NOT_FOUND
            }
            it("확정한 아이콘을 재확인해도 임시 업로드로 다시 덮어쓰지 않는다") {
                service.confirm(7L, uploadKey) shouldBe iconUrl
                service.confirm(7L, uploadKey) shouldBe iconUrl
                upload.confirmed shouldBe true
                verify(exactly = 1) { storage.finalizeUpload(uploadKey, key, "image/png") }
            }
            it("발급 대상 팀이 없는 기존 업로드를 공개 경로로 복사하지 않는다") {
                upload.teamId = null
                shouldThrow<ExpectedException> { service.confirm(7L, uploadKey) }.statusCode shouldBe
                    HttpStatus.BAD_REQUEST
                upload.confirmed shouldBe false
                verify(exactly = 0) { storage.finalizeUpload(any(), any(), any()) }
            }
            it("발급 후 대상 팀이 삭제된 업로드를 공개 경로로 복사하지 않는다") {
                every { teams.existsById(10L) } returns false
                shouldThrow<ExpectedException> { service.confirm(7L, uploadKey) }.statusCode shouldBe
                    HttpStatus.BAD_REQUEST
                upload.confirmed shouldBe false
                verify(exactly = 0) { storage.finalizeUpload(any(), any(), any()) }
            }
        }

        describe("팀 아이콘 연결은") {
            it("업로드 확인을 우회한 연결을 거부한다") {
                val target = team()
                shouldThrow<ExpectedException> { service.replace(7L, target, iconUrl) }.statusCode shouldBe
                    HttpStatus.BAD_REQUEST
                target.iconUrl shouldBe null
            }
            it("같은 팀이어도 다른 사용자의 업로드 연결을 거부한다") {
                upload.confirmed = true
                val target = team()
                shouldThrow<ExpectedException> { service.replace(8L, target, iconUrl) }.statusCode shouldBe
                    HttpStatus.FORBIDDEN
                target.iconUrl shouldBe null
            }
            it("다른 팀으로 발급한 아이콘은 업로더도 연결할 수 없다") {
                upload.confirmed = true
                upload.teamId = 20L
                shouldThrow<ExpectedException> { service.replace(7L, team(), iconUrl) }.statusCode shouldBe
                    HttpStatus.FORBIDDEN
                upload.teamId shouldBe 20L
            }
            it("발급 대상 팀 없이 확인된 기존 업로드는 업로더도 연결할 수 없다") {
                upload.confirmed = true
                upload.teamId = null
                val target = team(icon = "https://storage.example.com/bucket/team-icons/previous.png")
                shouldThrow<ExpectedException> { service.replace(7L, target, iconUrl) }.statusCode shouldBe
                    HttpStatus.FORBIDDEN
                target.iconUrl shouldBe "https://storage.example.com/bucket/team-icons/previous.png"
            }
            it("폐기한 아이콘을 다시 연결할 수 없다") {
                upload.confirmed = true
                upload.retired = true
                shouldThrow<ExpectedException> { service.replace(7L, team(), iconUrl) }.statusCode shouldBe
                    HttpStatus.BAD_REQUEST
            }
            it("발급 대상 팀에 확인된 본인 업로드를 연결한다") {
                upload.confirmed = true
                val target = team()
                service.replace(7L, target, iconUrl)
                target.iconUrl shouldBe iconUrl
            }
            it("같은 URL의 기존 아이콘은 다시 연결하지 않는다") {
                service.replace(7L, team(icon = iconUrl), iconUrl)
                verify(exactly = 0) { uploads.findByObjectKeyForUpdate(any()) }
            }
        }

        describe("아이콘 제거는") {
            it("소유한 객체만 폐기하고 삭제한다") {
                upload.confirmed = true
                upload.teamId = 10L
                val target = team(icon = iconUrl)
                service.clear(target)
                target.iconUrl shouldBe null
                upload.retired shouldBe true
                verify(exactly = 1) { storage.deleteObject(key) }
            }
            it("다른 팀의 객체가 기존 URL에 들어 있어도 삭제하지 않는다") {
                upload.confirmed = true
                upload.teamId = 20L
                service.clear(team(icon = iconUrl))
                upload.retired shouldBe false
                verify(exactly = 0) { storage.deleteObject(any()) }
            }
            it("소유권 기록 없는 기존 객체는 연결만 해제한다") {
                every { uploads.findByObjectKeyForUpdate(key) } returns null
                val target = team(icon = iconUrl)
                service.clear(target)
                target.iconUrl shouldBe null
                verify(exactly = 0) { storage.deleteObject(any()) }
            }
            it("프로필이나 채팅 파일을 가리키는 기존 URL은 삭제하지 않는다") {
                val foreignUrl = "https://storage.example.com/bucket/chat-files/1/42/file.png"
                every { storage.managedObjectKeyOrNull(foreignUrl) } returns null
                service.clear(team(icon = foreignUrl))
                verify(exactly = 0) { storage.deleteObject(any()) }
            }
        }
    })
