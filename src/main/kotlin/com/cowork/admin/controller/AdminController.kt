package com.cowork.admin.controller

import com.cowork.admin.dto.AdminStatsResponse
import com.cowork.admin.service.AdminService
import com.cowork.global.security.AuthUser
import com.cowork.global.security.CurrentUser
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
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
}
