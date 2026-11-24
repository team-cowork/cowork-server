package com.cowork.preference.domain.access.effective.service

import com.cowork.preference.domain.access.effective.model.EffectivePermissionEvaluator
import com.cowork.preference.domain.access.effective.model.EffectivePermissionQuery
import com.cowork.preference.domain.access.effective.model.EffectivePermissionResult
import com.cowork.preference.domain.access.effective.model.PermissionScope
import com.cowork.preference.domain.access.exception.ProjectionNotReadyException
import com.cowork.preference.domain.access.permission.PermissionDecision
import com.cowork.preference.domain.access.permission.PermissionKeys
import com.cowork.preference.domain.access.permission.PolicyRole
import com.cowork.preference.domain.access.service.ChannelAccessGuard
import com.cowork.preference.domain.access.service.ProjectAccessGuard
import com.cowork.preference.domain.access.service.TeamMembershipGuard
import com.cowork.preference.domain.accountrole.AccountRolePolicy
import com.cowork.preference.domain.accountrole.AccountRoleRepository
import com.cowork.preference.domain.channelrole.repository.ChannelRolePolicyRepository
import com.cowork.preference.domain.projectrole.repository.ProjectRoleRepository
import com.cowork.preference.domain.teamrole.model.TeamRoleDefinition
import com.cowork.preference.domain.teamrole.repository.TeamRoleRepository
import com.cowork.preference.global.security.RequesterContext
import io.vertx.sqlclient.Pool
import java.time.Clock

/**
 * 요청자 본인의 유효 권한을 Preference가 소유한 정책 원본으로 평가한다.
 * 채널과 프로젝트는 리소스가 속한 팀의 활성 멤버면 접근 권한이 없어도 조회할 수 있다. 판정 근거에는 요청자의 역할만 담는다.
 * 다른 서비스는 Kafka projection으로 판정하므로 정책 변경 직후에는 결과가 잠시 다를 수 있다.
 */
class EffectivePermissionService(
    private val pool: Pool,
    private val teamMembershipGuard: TeamMembershipGuard,
    private val channelAccessGuard: ChannelAccessGuard,
    private val projectAccessGuard: ProjectAccessGuard,
    private val accountRoleRepository: AccountRoleRepository,
    private val teamRoleRepository: TeamRoleRepository,
    private val channelRolePolicyRepository: ChannelRolePolicyRepository,
    private val projectRoleRepository: ProjectRoleRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun evaluate(requester: RequesterContext, query: EffectivePermissionQuery): EffectivePermissionResult {
        val accountId = requester.userId
        val result = when (val scope = query.scope) {
            PermissionScope.Account -> ScopeResult(null, evaluateAccount(accountId, query.keys))
            is PermissionScope.Team -> ScopeResult(scope.teamId, evaluateTeam(requester, scope, query.keys))
            is PermissionScope.Channel -> evaluateChannel(accountId, scope, query.keys)
            is PermissionScope.Project -> evaluateProject(requester, scope, query.keys)
        }
        return EffectivePermissionResult(
            accountId = accountId,
            scope = query.scope,
            teamId = result.teamId,
            evaluatedAt = clock.instant(),
            decisions = result.decisions,
            explain = query.explain,
            channelArchived = result.channelArchived,
        )
    }

    private suspend fun evaluateAccount(accountId: Long, keys: List<String>): Map<String, PermissionDecision> {
        val memberTeamRoles = if (AccountRolePolicy.CREATE_TEAM in keys) {
            teamMembershipGuard.requireProjectionReady()
            teamRoleRepository.findRolesInMemberTeams(pool, accountId).map {
                PolicyRole(it.id, it.name, it.permissions, it.isDefault, it.teamId)
            }
        } else {
            emptyList()
        }
        return EffectivePermissionEvaluator.account(
            accountRoleRepository.policyRoles(pool, accountId),
            memberTeamRoles,
            keys,
        )
    }

    private suspend fun evaluateTeam(
        requester: RequesterContext,
        scope: PermissionScope.Team,
        keys: List<String>,
    ): Map<String, PermissionDecision> {
        teamMembershipGuard.requireMember(scope.teamId, requester)
        return EffectivePermissionEvaluator.team(teamRoles(scope.teamId, requester.userId), keys)
    }

    private suspend fun evaluateChannel(
        accountId: Long,
        scope: PermissionScope.Channel,
        keys: List<String>,
    ): ScopeResult {
        val channel = channelAccessGuard.requireChannelTeamMember(scope.channelId, accountId)
        val teamId = requireNotNull(channel.teamId) { "팀 채널만 권한을 조회할 수 있습니다." }
        val decisions = EffectivePermissionEvaluator.channel(
            teamRoles(teamId, accountId),
            channelRolePolicyRepository.findOverwritesForRead(pool, teamId, scope.channelId),
            // ChannelAccessGuard와 같이 아직 확인하지 못한 비공개 여부는 비공개로 판정한다.
            isPrivate = channel.isPrivate != false,
            accessPermission = when (channel.channelType) {
                "VOICE" -> PermissionKeys.VOICE_CONNECT
                "TEXT" -> PermissionKeys.MESSAGE_READ
                else -> throw ProjectionNotReadyException("team channel type is not known yet")
            },
            keys = keys,
        )
        // 보관은 권한 정책이 아니라 채널 상태이므로 판정 값은 바꾸지 않고 응답에만 표시한다.
        return ScopeResult(teamId, decisions, channel.archived)
    }

    private suspend fun evaluateProject(
        requester: RequesterContext,
        scope: PermissionScope.Project,
        keys: List<String>,
    ): ScopeResult {
        val project = projectAccessGuard.requireTeamMember(scope.projectId, requester)
        val decisions = EffectivePermissionEvaluator.project(
            teamRoles(project.teamId, requester.userId),
            projectRoleRepository.findAccountRoles(scope.projectId, requester.userId, pool),
            project.isPrivate,
            projectRoleRepository.effectivePermissions(scope.projectId, requester.userId, pool),
            keys,
        )
        return ScopeResult(project.teamId, decisions)
    }

    private suspend fun teamRoles(teamId: Long, accountId: Long): List<PolicyRole> =
        teamRoleRepository.findAccountRoles(pool, teamId, accountId).map { it.toPolicyRole() }

    private fun TeamRoleDefinition.toPolicyRole() = PolicyRole(id, name, permissions, isDefault)

    private data class ScopeResult(
        val teamId: Long?,
        val decisions: Map<String, PermissionDecision>,
        val channelArchived: Boolean? = null,
    )
}
