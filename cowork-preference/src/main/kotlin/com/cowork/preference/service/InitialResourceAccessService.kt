package com.cowork.preference.service

import com.cowork.preference.domain.ChannelLifecycleProjection
import com.cowork.preference.domain.ChannelRolePermissionResolver
import com.cowork.preference.domain.PermissionKeys
import com.cowork.preference.domain.ProjectLifecycleProjection
import com.cowork.preference.domain.TeamMemberProjection
import com.cowork.preference.messaging.ChannelLifecycleEvent
import com.cowork.preference.messaging.PreferenceEvents
import com.cowork.preference.messaging.ProjectLifecycleEvent
import com.cowork.preference.messaging.ProjectionReadiness
import com.cowork.preference.repository.ChannelRolePolicyRepository
import com.cowork.preference.repository.InitialResourceAccessRepository
import com.cowork.preference.repository.PreferenceOutboxRepository
import com.cowork.preference.repository.ProjectRoleRepository
import com.cowork.preference.repository.TeamMemberProjectionRepository
import com.cowork.preference.repository.TeamRoleRepository
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.SqlClient
import org.slf4j.LoggerFactory
import java.time.Instant

class InitialResourceAccessService(
    private val ledger: InitialResourceAccessRepository,
    private val members: TeamMemberProjectionRepository,
    private val roles: TeamRoleRepository,
    private val channelPolicies: ChannelRolePolicyRepository,
    private val projectRoles: ProjectRoleRepository,
    private val outbox: PreferenceOutboxRepository,
    private val teamReadiness: ProjectionReadiness,
) {
    private val log = LoggerFactory.getLogger(InitialResourceAccessService::class.java)

    suspend fun channel(client: SqlClient, event: ChannelLifecycleEvent, current: ChannelLifecycleProjection) {
        if (event.deleted || current.deleted) {
            ledger.claim(client, "CHANNEL", event.channelId)
            return
        }
        val seed = event.initialRolePolicySeed ?: return
        if (!ledger.claim(client, "CHANNEL", event.channelId)) return
        val teamId = event.teamId ?: return
        if (current.teamId != teamId || roles.isTeamDeleted(client, teamId)) return
        val actor = actor(
            client,
            teamId,
            seed.actorId,
            seed.actorMembershipVersion,
            seed.actorAuthorizationVersion,
        ) ?: return
        if (ledger.hasChannelPolicyHistory(client, teamId, event.channelId)) return
        val actorRoles = roles.findAccountRoles(client, teamId, seed.actorId)
        val teamRoles = roles.findRoles(client, teamId).associateBy { it.id }
        val survivingOverwrites = seed.overwrites.filter { it.roleId in teamRoles }
        if (survivingOverwrites.size != seed.overwrites.size) {
            log.warn(
                "Skip deleted roles in initial channel access [teamId={}, channelId={}, skippedRoles={}]",
                teamId,
                event.channelId,
                seed.overwrites.size - survivingOverwrites.size,
            )
        }
        fun skip(reason: String) {
            log.warn(
                "Skip initial channel access [teamId={}, channelId={}, reason={}]",
                teamId,
                event.channelId,
                reason,
            )
        }
        if (survivingOverwrites.isEmpty()) return skip("No requested roles remain")
        if (actor.builtInRole !in MANAGERS) {
            fun allowed(permission: String) = actorRoles.any { it.permissions[permission] == true }
            if (!allowed(
                    PermissionKeys.MANAGE_CHANNELS,
                )
            ) {
                return skip("Creator no longer has channel management permission")
            }
            if (survivingOverwrites.any { overwrite -> overwrite.permissions.fieldNames().any { !allowed(it) } }) {
                return skip("Creator does not hold the remaining overwrite permissions")
            }
            val accessPermission = when (current.channelType) {
                "TEXT" -> PermissionKeys.MESSAGE_READ
                "VOICE" -> PermissionKeys.VOICE_CONNECT
                else -> return skip("Channel type is unsupported")
            }
            val overwrites = survivingOverwrites.associateBy { it.roleId }
            val canAccess = ChannelRolePermissionResolver.resolve(
                actorRoles.map { role ->
                    ChannelRolePermissionResolver.RolePermission(
                        role.isDefault,
                        role.permissions[accessPermission] == true,
                        overwrites[role.id]?.permissions?.getBoolean(accessPermission),
                    )
                },
                accessPermission,
                current.isPrivate != false,
            )
            if (!canAccess) return skip("Remaining overwrites do not grant creator access")
        }
        for (overwrite in survivingOverwrites) {
            val state = channelPolicies.upsertPolicy(
                client,
                teamId,
                event.channelId,
                overwrite.roleId,
                overwrite.permissions,
            )
            outbox.enqueue(
                client,
                PreferenceEvents.channelRolePolicyState(
                    teamId,
                    event.channelId,
                    overwrite.roleId,
                    state.permissions,
                    state.stateOccurredAt,
                    snapshot = false,
                ),
            )
        }
    }

    suspend fun project(client: SqlClient, event: ProjectLifecycleEvent, current: ProjectLifecycleProjection): Boolean {
        if (event.deleted || current.deleted) {
            ledger.claim(client, "PROJECT", event.projectId)
            return false
        }
        val seed = event.initialReadGrant ?: return false
        if (!ledger.claim(client, "PROJECT", event.projectId)) return false
        if (current.teamId != event.teamId || current.createdBy != seed.accountId || !current.isPrivate) return false
        if (roles.isTeamDeleted(client, event.teamId)) return false
        val actor = actor(
            client,
            event.teamId,
            seed.accountId,
            seed.sourceMembershipVersion,
            seed.sourceAuthorizationVersion,
        ) ?: return false
        if (actor.builtInRole in MANAGERS || ledger.hasProjectAccessHistory(client, event.projectId)) return false
        val actorRoles = roles.findAccountRoles(client, event.teamId, seed.accountId)
        if (listOf(PermissionKeys.MANAGE_PROJECTS, PermissionKeys.PROJECT_READ).any { permission ->
                actorRoles.none { it.permissions[permission] == true }
            }
        ) {
            return false
        }
        projectRoles.insertRole(event.projectId, INITIAL_PROJECT_ROLE_NAME, JsonObject().put("read", true), 0, client)
        projectRoles.assignRole(seed.accountId, event.projectId, INITIAL_PROJECT_ROLE_NAME, client)
        return true
    }

    private suspend fun actor(
        client: SqlClient,
        teamId: Long,
        accountId: Long,
        membershipVersion: Instant,
        authorizationVersion: Instant,
    ): TeamMemberProjection? {
        check(teamReadiness.isReady) { "Team projection is not ready for initial access" }
        val member = members.find(client, teamId, accountId)
        check(member != null && !member.sourceOccurredAt.isBefore(membershipVersion)) {
            "Team membership is behind initial access version"
        }
        return member.takeIf { !it.deleted && it.authorizationVersion == authorizationVersion }
    }

    companion object {
        const val INITIAL_PROJECT_ROLE_NAME = "초기 접근"
        private val MANAGERS = setOf("OWNER", "ADMIN")
    }
}
