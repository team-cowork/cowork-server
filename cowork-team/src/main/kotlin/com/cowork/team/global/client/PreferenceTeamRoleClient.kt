package com.cowork.team.global.client

import com.cowork.team.domain.teamRole.presentation.data.request.CreateTeamRoleRequest
import com.cowork.team.domain.teamRole.presentation.data.request.UpdateTeamRoleRequest
import com.cowork.team.domain.teamRole.presentation.data.response.TeamRoleResponse
import org.springframework.cloud.openfeign.FeignClient
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody

/**
 * 외부 Gateway에 노출되지 않는 internal 역할 command의 생성 ID와 검증 오류를 즉시 반환받기 위한 동기 HTTP 예외다.
 * 역할 조회는 `preference.team-role.changed` 로컬 projection만 사용한다.
 */
@FeignClient(name = "cowork-preference")
interface PreferenceTeamRoleClient {

    @PostMapping("/internal/preferences/team/{teamId}/roles")
    fun createRole(@PathVariable teamId: Long, @RequestBody request: CreateTeamRoleRequest): TeamRoleResponse

    @PatchMapping("/internal/preferences/team/{teamId}/roles/{roleId}")
    fun updateRole(
        @PathVariable teamId: Long,
        @PathVariable roleId: Long,
        @RequestBody request: UpdateTeamRoleRequest,
    ): TeamRoleResponse

    @DeleteMapping("/internal/preferences/team/{teamId}/roles/{roleId}")
    fun deleteRole(@PathVariable teamId: Long, @PathVariable roleId: Long)

    @PostMapping("/internal/preferences/team/{teamId}/roles/{roleId}/members")
    fun assignRole(
        @PathVariable teamId: Long,
        @PathVariable roleId: Long,
        @RequestBody body: Map<String, Long>,
    ): TeamRoleResponse

    @DeleteMapping("/internal/preferences/team/{teamId}/roles/{roleId}/members/{accountId}")
    fun revokeRole(@PathVariable teamId: Long, @PathVariable accountId: Long, @PathVariable roleId: Long)
}
