package com.cowork.team.domain.team.service

import com.cowork.team.domain.team.entity.Team
import com.cowork.team.domain.team.entity.TeamIconUpload
import com.cowork.team.domain.team.repository.TeamIconUploadRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException

class TeamIconOwnershipServiceTest :
    DescribeSpec({
        val key = "team-icons/12345678-1234-1234-1234-123456789012.png"
        val uploadKey = "team-icon-uploads/7/12345678-1234-1234-1234-123456789012.png"
        val iconUrl = "https://storage.example.com/bucket/$key"
        lateinit var uploads: TeamIconUploadRepository
        lateinit var storage: S3Service
        lateinit var service: TeamIconOwnershipService
        lateinit var upload: TeamIconUpload

        fun team(id: Long = 10L, icon: String? = null) = Team(id, "team", null, icon, 7L)

        beforeEach {
            uploads = mockk()
            storage = mockk(relaxed = true)
            upload = TeamIconUpload(key, uploadKey, 7L, "image/png")
            every { storage.extractObjectKey(iconUrl) } returns key
            every { storage.managedObjectKeyOrNull(iconUrl) } returns key
            every { storage.buildPublicUrl(key) } returns iconUrl
            every { uploads.findByObjectKeyForUpdate(key) } returns upload
            every { uploads.findByUploadKeyForUpdate(uploadKey) } returns upload
            service = TeamIconOwnershipService(uploads, storage)
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
        }

        describe("팀 아이콘 연결은") {
            it("업로드 확인을 우회한 연결을 거부한다") {
                val target = team()
                shouldThrow<ExpectedException> { service.replace(7L, target, iconUrl) }.statusCode shouldBe
                    HttpStatus.BAD_REQUEST
                target.iconUrl shouldBe null
            }
            it("다른 사용자의 미사용 아이콘 연결을 거부한다") {
                upload.confirmed = true
                shouldThrow<ExpectedException> { service.replace(8L, team(), iconUrl) }.statusCode shouldBe
                    HttpStatus.FORBIDDEN
                upload.teamId shouldBe null
            }
            it("원래 업로더도 다른 팀에 연결한 아이콘을 가져올 수 없다") {
                upload.confirmed = true
                upload.teamId = 20L
                shouldThrow<ExpectedException> { service.replace(7L, team(), iconUrl) }.statusCode shouldBe
                    HttpStatus.FORBIDDEN
                upload.teamId shouldBe 20L
            }
            it("폐기한 아이콘을 다시 연결할 수 없다") {
                upload.confirmed = true
                upload.retired = true
                shouldThrow<ExpectedException> { service.replace(7L, team(), iconUrl) }.statusCode shouldBe
                    HttpStatus.BAD_REQUEST
            }
            it("확인된 본인 업로드를 한 팀에 귀속한다") {
                upload.confirmed = true
                val target = team()
                service.replace(7L, target, iconUrl)
                target.iconUrl shouldBe iconUrl
                upload.teamId shouldBe 10L
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
