package com.cowork.user

import com.cowork.user.domain.User
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class UserTest {

    private fun user() = User(dataGsmId = 1, name = "홍길동", email = "a@gsm.hs.kr", sex = "MAN")

    @Test
    fun `null로 전달한 프로필 항목은 기존 값을 유지한다`() {
        val user = user()
        user.updateProfile("길동", "소개", null)

        user.updateProfile(null, null, "백엔드")

        assertEquals("길동", user.nickname)
        assertEquals("소개", user.description)
        assertEquals("백엔드", user.specialty)
    }

    @Test
    fun `공백만 있는 프로필 항목은 null로 저장한다`() {
        val user = user()
        user.updateProfile("길동", null, null)

        user.updateProfile("  ", null, null)

        assertNull(user.nickname)
    }

    @Test
    fun `학생 정보를 동기화하면 학적 정보를 모두 덮어쓴다`() {
        val user = user()

        user.syncStudentInfo("김철수", "b@gsm.hs.kr", "WOMAN", 3, 2, 3201, "FRONTEND", "LEADER", "kim")

        assertEquals("김철수", user.name)
        assertEquals(3201, user.studentNumber)
        assertEquals("kim", user.githubId)
    }
}
