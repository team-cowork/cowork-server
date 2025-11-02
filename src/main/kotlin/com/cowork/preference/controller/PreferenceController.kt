package com.cowork.preference.controller

import com.cowork.global.error.BusinessException
import com.cowork.global.security.AuthUser
import com.cowork.global.security.CurrentUser
import com.cowork.preference.domain.ResourceType
import com.cowork.preference.dto.SettingsResponse
import com.cowork.preference.service.PreferenceService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/preferences")
class PreferenceController(
    private val preferenceService: PreferenceService,
) {

    @GetMapping("/{resourceType}/{resourceId}")
    fun getSettings(
        @CurrentUser user: AuthUser,
        @PathVariable resourceType: String,
        @PathVariable resourceId: Long,
    ): SettingsResponse = preferenceService.getSettings(parse(resourceType), resourceId, user.id)

    private fun parse(resourceType: String): ResourceType =
        ResourceType.fromPath(resourceType)
            ?: throw BusinessException(HttpStatus.NOT_FOUND, "지원하지 않는 설정 유형입니다.")
}
