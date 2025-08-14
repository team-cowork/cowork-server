package com.cowork.user

import com.cowork.global.storage.StorageService
import com.cowork.user.domain.User
import com.cowork.user.dto.StudentProfile
import com.cowork.user.repository.UserRepository
import com.cowork.user.service.UserService
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

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
}
