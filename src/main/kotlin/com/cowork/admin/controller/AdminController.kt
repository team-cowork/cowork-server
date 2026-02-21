package com.cowork.admin.controller

import com.cowork.admin.dto.AdminStatsResponse
import com.cowork.admin.dto.ChangeUserRoleRequest
import com.cowork.admin.service.AdminService
import com.cowork.global.security.AuthUser
import com.cowork.global.security.CurrentUser
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@Tag(name = "운영")
@RestController
@RequestMapping("/admin")
class AdminController(
    private val adminService: AdminService,
) {

    @Operation(summary = "서비스 현황 통계 조회")
    @GetMapping("/stats")
    fun getStats(@CurrentUser user: AuthUser): AdminStatsResponse = adminService.getStats(user)

    @Operation(summary = "사용자 권한 변경")
    @PatchMapping("/users/{userId}/role")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun changeUserRole(
        @CurrentUser user: AuthUser,
        @PathVariable userId: Long,
        @Valid @RequestBody request: ChangeUserRoleRequest,
    ) = adminService.changeUserRole(user, userId, request.role)
}
