package com.cowork.user

import com.cowork.global.error.BusinessException
import com.cowork.global.storage.PresignedUploadRequest
import com.cowork.global.storage.StorageService
import com.cowork.user.domain.User
import com.cowork.user.dto.StudentProfile
import com.cowork.user.dto.UpdateProfileRequest
import com.cowork.user.repository.UserRepository
import com.cowork.user.service.UserService
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import java.util.Optional

class UserServiceTest {

    private val userRepository = mockk<UserRepository>(relaxed = true)
    private val storageService = mockk<StorageService>(relaxed = true)
    private val service = UserService(userRepository, storageService)

    private fun profile(name: String = "홍길동") = StudentProfile(
        dataGsmId = 10,
        name = name,
        email = "s25001@gsm.hs.kr",
        sex = "MAN",
        grade = 2,
        classNum = 1,
        studentNumber = 2101,
        major = "BACKEND",
        studentRole = null,
        githubId = "hong",
    )

    private fun user(id: Long = 1) =
        User(dataGsmId = 10, name = "홍길동", email = "s25001@gsm.hs.kr", sex = "MAN", grade = 2, id = id)

    @Test
    fun `처음 로그인한 학생은 새 사용자로 저장한다`() {
        val saved = slot<User>()
        every { userRepository.findByDataGsmId(10) } returns null
        every { userRepository.save(capture(saved)) } answers { saved.captured }

        val result = service.upsertStudent(profile())

        assertEquals("홍길동", result.name)
        assertEquals(2101, saved.captured.studentNumber)
    }

    @Test
    fun `이미 가입한 학생은 DataGSM 정보로 갱신한다`() {
        val existing = user()
        every { userRepository.findByDataGsmId(10) } returns existing

        service.upsertStudent(profile(name = "김철수"))

        assertEquals("김철수", existing.name)
        verify(exactly = 0) { userRepository.save(any()) }
    }

    @Test
    fun `존재하지 않는 사용자를 조회하면 404를 반환한다`() {
        every { userRepository.findById(99) } returns Optional.empty()

        val exception = assertThrows<BusinessException> { service.getUser(99) }

        assertEquals(HttpStatus.NOT_FOUND, exception.status)
    }

    @Test
    fun `빈 문자열로 수정한 프로필 항목은 비운다`() {
        val existing = user()
        existing.updateProfile("길동", "소개", "백엔드")
        every { userRepository.findById(1) } returns Optional.of(existing)

        val response = service.updateProfile(1, UpdateProfileRequest(nickname = " ", description = null))

        assertNull(response.nickname)
        assertEquals("소개", response.description)
    }

    @Test
    fun `공백 키워드는 조건 없이 검색한다`() {
        val pageable = PageRequest.of(0, 20)
        every { userRepository.search(null, null, null, pageable) } returns PageImpl(listOf(user()))

        val page = service.search("   ", null, null, pageable)

        assertEquals(1, page.totalElements)
        verify { userRepository.search(null, null, null, pageable) }
    }

    @Test
    fun `검색 키워드의 앞뒤 공백을 제거한다`() {
        val pageable = PageRequest.of(0, 20)
        every { userRepository.search(any(), any(), any(), any()) } returns PageImpl(emptyList())

        service.search("  길동 ", "BACKEND", 2, pageable)

        verify { userRepository.search("길동", "BACKEND", 2, pageable) }
    }

    @Test
    fun `프로필 이미지 업로드 URL은 사용자 경로 아래에 만든다`() {
        every { storageService.createUploadUrl(any(), "image/png") } returns "https://upload"

        val response = service.createProfileImageUploadUrl(1, PresignedUploadRequest("image/png", 1024))

        assertTrue(response.objectKey.startsWith("profiles/1/"))
        assertTrue(response.objectKey.endsWith(".png"))
        assertEquals("https://upload", response.uploadUrl)
    }

    @Test
    fun `다른 사용자 경로의 이미지 키는 확정할 수 없다`() {
        val exception = assertThrows<BusinessException> { service.confirmProfileImage(1, "profiles/2/a.png") }

        assertEquals(HttpStatus.BAD_REQUEST, exception.status)
    }

    @Test
    fun `업로드되지 않은 이미지는 확정할 수 없다`() {
        every { storageService.findObjectSize("profiles/1/a.png") } returns null

        val exception = assertThrows<BusinessException> { service.confirmProfileImage(1, "profiles/1/a.png") }

        assertEquals(HttpStatus.CONFLICT, exception.status)
    }
}
