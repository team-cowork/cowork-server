package com.cowork.team.domain.teamRole.service.support

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException

class TeamRolePermissionSchemaTest :
    DescribeSpec({
        val schema = TeamRolePermissionSchema()

        describe("TeamRolePermissionSchema 클래스의") {
            describe("canonicalize 메서드는") {
                it("누락된 권한을 false로 채운 전체 권한을 반환한다") {
                    val permissions = schema.canonicalize(mapOf("message_send" to true))

                    permissions.keys shouldBe TeamRolePermissionSchema.KEYS
                    permissions["message_send"] shouldBe true
                    permissions[TeamRolePermissionSchema.MANAGE_ROLES] shouldBe false
                }
            }

            describe("validate 메서드는") {
                it("보낸 권한만 반환한다") {
                    schema.validate(mapOf("message_send" to false)) shouldBe mapOf("message_send" to false)
                }

                it("지원하지 않는 권한 키를 BAD_REQUEST로 거부한다") {
                    listOf("MANAGE_ROLES", "channel_member_manage").forEach { key ->
                        val error = shouldThrow<ExpectedException> { schema.validate(mapOf(key to true)) }
                        error.statusCode shouldBe HttpStatus.BAD_REQUEST
                    }
                }

                it("boolean이 아닌 권한 값을 BAD_REQUEST로 거부한다") {
                    val error = shouldThrow<ExpectedException> { schema.validate(mapOf("message_send" to "true")) }

                    error.statusCode shouldBe HttpStatus.BAD_REQUEST
                }
            }
        }
    })
