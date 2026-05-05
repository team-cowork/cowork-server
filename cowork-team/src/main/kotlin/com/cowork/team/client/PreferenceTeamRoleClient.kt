package com.cowork.team.client

import com.cowork.team.dto.CreateTeamRoleRequest
import com.cowork.team.dto.TeamRoleResponse
import com.cowork.team.dto.UpdateTeamRoleRequest
import org.springframework.core.ParameterizedTypeReference
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

@Component
class PreferenceTeamRoleClient(
    private val preferenceRestClient: RestClient,
) {

    fun getRoles(teamId: Long): List<TeamRoleResponse> =
        preferenceRestClient.get()
            .uri("/preferences/team/{teamId}/roles", teamId)
            .retrieve()
            .body(object : ParameterizedTypeReference<List<TeamRoleResponse>>() {})
            ?: emptyList()

    fun getMemberRoles(teamId: Long, accountId: Long): List<TeamRoleResponse> =
        preferenceRestClient.get()
            .uri("/preferences/team/{teamId}/roles/members/{accountId}", teamId, accountId)
            .retrieve()
            .body(object : ParameterizedTypeReference<List<TeamRoleResponse>>() {})
            ?: emptyList()

    fun createRole(teamId: Long, request: CreateTeamRoleRequest): TeamRoleResponse =
        requireNotNull(
            preferenceRestClient.post()
                .uri("/preferences/team/{teamId}/roles", teamId)
                .body(request)
                .retrieve()
                .body(TeamRoleResponse::class.java)
        )

    fun updateRole(teamId: Long, roleId: Long, request: UpdateTeamRoleRequest): TeamRoleResponse =
        requireNotNull(
            preferenceRestClient.patch()
                .uri("/preferences/team/{teamId}/roles/{roleId}", teamId, roleId)
                .body(request)
                .retrieve()
                .body(TeamRoleResponse::class.java)
        )

    fun deleteRole(teamId: Long, roleId: Long) {
        preferenceRestClient.delete()
            .uri("/preferences/team/{teamId}/roles/{roleId}", teamId, roleId)
            .retrieve()
            .toBodilessEntity()
    }

    fun assignRole(teamId: Long, accountId: Long, roleId: Long): TeamRoleResponse {
        preferenceRestClient.post()
            .uri("/preferences/team/{teamId}/roles/{roleId}/members", teamId, roleId)
            .body(mapOf("accountId" to accountId))
            .retrieve()
            .toBodilessEntity()

        return getRoles(teamId).first { it.id == roleId }
    }

    fun revokeRole(teamId: Long, accountId: Long, roleId: Long) {
        preferenceRestClient.delete()
            .uri("/preferences/team/{teamId}/roles/{roleId}/members/{accountId}", teamId, roleId, accountId)
            .retrieve()
            .toBodilessEntity()
    }

    fun deleteMemberRoles(teamId: Long, accountId: Long) {
        preferenceRestClient.delete()
            .uri("/preferences/team/{teamId}/roles/members/{accountId}", teamId, accountId)
            .retrieve()
            .toBodilessEntity()
    }
}
