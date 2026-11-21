package com.cowork.preference.domain.accountrole

import com.cowork.preference.domain.access.exception.ResourceAccessDeniedException
import com.cowork.preference.global.outbox.PreferenceOutboxRepository
import com.cowork.preference.global.security.RequesterContext
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.SqlConnection

class AccountRoleService(
    private val pool: Pool,
    private val repository: AccountRoleRepository,
    private val outbox: PreferenceOutboxRepository,
) {
    suspend fun list(): List<JsonObject> = repository.roles(pool)

    suspend fun assignments(requester: RequesterContext, accountId: Long = requester.userId): List<Long> =
        outbox.inTransaction { connection ->
            if (accountId != requester.userId &&
                !AccountRolePolicy.allows(
                    repository.policies(connection, requester.userId),
                    AccountRolePolicy.MANAGE_ROLES,
                )
            ) {
                throw ResourceAccessDeniedException("계정 역할을 조회할 권한이 없습니다.")
            }
            repository.assignments(connection, accountId)
        }

    suspend fun save(requester: RequesterContext, id: Long?, name: String, permissions: JsonObject): JsonObject {
        AccountRolePolicy.validate(name, permissions.map)
        return mutate(requester) { connection, policies ->
            if (permissions.map.any { (action, allowed) ->
                    allowed == true &&
                        !AccountRolePolicy.allows(policies, action)
                }
            ) {
                throw ResourceAccessDeniedException("보유하지 않은 계정 권한은 부여할 수 없습니다.")
            }
            if (id == null) {
                repository.create(connection, name, permissions)
            } else {
                repository.update(connection, id, name, permissions)
            }
        }
    }

    suspend fun delete(requester: RequesterContext, id: Long) = mutate(requester) { connection, _ ->
        repository.delete(connection, id)
    }

    suspend fun assign(requester: RequesterContext, roleId: Long, accountId: Long, deleted: Boolean) =
        mutate(requester) {
                connection,
                policies,
            ->
            val role = repository.roles(connection).firstOrNull { it.getLong("id") == roleId }
                ?: throw IllegalArgumentException("계정 역할을 찾을 수 없습니다.")
            if (!deleted && role.getJsonObject("permissions").map.any { (action, allowed) ->
                    allowed == true && !AccountRolePolicy.allows(policies, action)
                }
            ) {
                throw ResourceAccessDeniedException("보유하지 않은 계정 권한은 부여할 수 없습니다.")
            }
            repository.assign(connection, roleId, accountId, deleted)
        }

    private suspend fun <T> mutate(
        requester: RequesterContext,
        action: suspend (SqlConnection, List<Map<String, Boolean>>) -> T,
    ): T = outbox.inTransaction { connection ->
        val policies = repository.policies(connection, requester.userId)
        if (!AccountRolePolicy.allows(policies, AccountRolePolicy.MANAGE_ROLES)) {
            throw ResourceAccessDeniedException("계정 역할을 관리할 권한이 없습니다.")
        }
        action(connection, policies)
    }
}
