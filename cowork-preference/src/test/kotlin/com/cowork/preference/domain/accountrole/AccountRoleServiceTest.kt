package com.cowork.preference.domain.accountrole

import com.cowork.preference.domain.access.exception.ResourceAccessDeniedException
import com.cowork.preference.global.outbox.PreferenceOutboxRepository
import com.cowork.preference.global.security.RequesterContext
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.SqlConnection

class AccountRoleServiceTest :
    DescribeSpec({
        val repository = mockk<AccountRoleRepository>()
        val outbox = mockk<PreferenceOutboxRepository>()
        val connection = mockk<SqlConnection>()
        val service = AccountRoleService(mockk(), repository, outbox)
        val requester = RequesterContext(7L)
        beforeEach {
            coEvery { outbox.inTransaction<JsonObject>(any()) } coAnswers {
                firstArg<suspend (SqlConnection) -> JsonObject>().invoke(connection)
            }
            coEvery { repository.policies(connection, 7L) } returns emptyList()
        }

        it("관리 권한이 회수되면 역할을 관리할 수 없다") {
            shouldThrow<ResourceAccessDeniedException> {
                service.save(requester, null, "themoment-admin", JsonObject())
            }
            coVerify(exactly = 0) { repository.create(any(), any(), any()) }
        }

        it("관리 권한만 가진 사용자는 보유하지 않은 생성 권한을 부여할 수 없다") {
            coEvery { repository.policies(connection, 7L) } returns listOf(mapOf("manage_account_roles" to true))
            shouldThrow<ResourceAccessDeniedException> {
                service.save(requester, null, "creator", JsonObject().put("create_team", true))
            }
        }

        it("역할 이름과 무관하게 보유한 권한을 조립해서 역할을 만든다") {
            coEvery { repository.policies(connection, 7L) } returns
                listOf(mapOf("manage_account_roles" to true), mapOf("create_team" to true))
            val permissions = JsonObject().put("create_team", true)
            val result = JsonObject().put("id", 3L).put("name", "themoment-admin").put("permissions", permissions)
            coEvery { repository.create(connection, "themoment-admin", permissions) } returns result
            service.save(requester, null, "themoment-admin", permissions) shouldBe result
        }

        it("지원하지 않는 정책과 불리언이 아닌 권한을 거부한다") {
            shouldThrow<IllegalArgumentException> { AccountRolePolicy.validate("role", mapOf("unknown" to true)) }
            shouldThrow<IllegalArgumentException> { AccountRolePolicy.validate("role", mapOf("create_team" to "true")) }
            AccountRolePolicy.allows(listOf(mapOf("unknown" to true)), "unknown") shouldBe false
        }

        it("본인의 역할은 조회할 수 있지만 관리 권한 없이 타인의 역할을 조회할 수 없다") {
            coEvery { outbox.inTransaction<List<Long>>(any()) } coAnswers {
                firstArg<suspend (SqlConnection) -> List<Long>>().invoke(connection)
            }
            coEvery { repository.assignments(connection, 7L) } returns listOf(1L)
            service.assignments(requester) shouldBe listOf(1L)
            shouldThrow<ResourceAccessDeniedException> { service.assignments(requester, 8L) }
            coVerify(exactly = 0) { repository.assignments(connection, 8L) }
        }
    })
