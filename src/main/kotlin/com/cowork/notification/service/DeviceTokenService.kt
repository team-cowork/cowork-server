package com.cowork.notification.service

import com.cowork.global.error.BusinessException
import com.cowork.notification.domain.DevicePlatform
import com.cowork.notification.domain.DeviceToken
import com.cowork.notification.repository.DeviceTokenRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class DeviceTokenService(
    private val deviceTokenRepository: DeviceTokenRepository,
) {

    @Transactional
    fun register(userId: Long, token: String, platform: DevicePlatform) {
        val existing = deviceTokenRepository.findByToken(token)
        if (existing == null) {
            deviceTokenRepository.save(DeviceToken(userId = userId, token = token, platform = platform))
        } else {
            existing.reassign(userId, platform)
        }
    }

    @Transactional
    fun delete(userId: Long, token: String) {
        val deviceToken = deviceTokenRepository.findByToken(token)
        if (deviceToken == null || deviceToken.userId != userId) {
            throw BusinessException(HttpStatus.NOT_FOUND, "등록된 기기 토큰을 찾을 수 없습니다.")
        }
        deviceTokenRepository.delete(deviceToken)
    }
}
