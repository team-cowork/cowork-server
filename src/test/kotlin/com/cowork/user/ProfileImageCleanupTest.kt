package com.cowork.user

import com.cowork.global.storage.StorageService
import com.cowork.user.domain.User
import com.cowork.user.repository.UserRepository
import com.cowork.user.service.UserService
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import software.amazon.awssdk.core.exception.SdkClientException
import java.util.Optional

class ProfileImageCleanupTest {

    private val userRepository = mockk<UserRepository>()
    private val storageService = mockk<StorageService>(relaxed = true)
    private val service = UserService(userRepository, storageService)

    @Test
    fun `이전 이미지 삭제에 실패해도 새 프로필 이미지는 반영한다`() {
        val user = User(dataGsmId = 10, name = "홍길동", email = "a@gsm.hs.kr", sex = "MAN", id = 1)
        user.changeProfileImage("profiles/1/old.png")
        every { userRepository.findById(1) } returns Optional.of(user)
        every { storageService.findObjectSize("profiles/1/new.png") } returns 1024
        every { storageService.delete("profiles/1/old.png") } throws SdkClientException.create("timeout")

        service.confirmProfileImage(1, "profiles/1/new.png")

        assertEquals("profiles/1/new.png", user.profileImageKey)
    }
}
