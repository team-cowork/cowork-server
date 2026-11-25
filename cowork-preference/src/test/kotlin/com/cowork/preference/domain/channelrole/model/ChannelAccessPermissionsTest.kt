package com.cowork.preference.domain.channelrole.model

import com.cowork.preference.domain.access.permission.PermissionKeys
import com.cowork.preference.domain.access.permission.PolicyRole
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class ChannelAccessPermissionsTest :
    DescribeSpec({
        describe("채널 권한 판정 결과") {
            val defaultRole = PolicyRole(
                1L,
                "everyone",
                mapOf(PermissionKeys.MESSAGE_READ to true, PermissionKeys.MESSAGE_SEND to true),
                isDefault = true,
            )
            val manager = PolicyRole(2L, "manager", mapOf(PermissionKeys.ADMINISTRATOR to true))

            fun resolve(
                roles: List<PolicyRole> = listOf(defaultRole),
                overwrites: Map<Long, Map<String, Boolean>> = emptyMap(),
                active: Boolean = true,
                deleted: Boolean = false,
                isPrivate: Boolean? = false,
                channelType: String? = "TEXT",
            ) = ChannelAccessPermissions.resolve(roles, overwrites, active, deleted, isPrivate, channelType)

            it("모든 CHANNEL_SCOPED 키의 결과를 담는다") {
                resolve().permissions.keys shouldBe PermissionKeys.CHANNEL_SCOPED
            }
            it("공개 텍스트 채널은 기본 역할의 권한을 따르고 비공개 접근은 허용하지 않는다") {
                val result = resolve()
                result.permissions[PermissionKeys.MESSAGE_READ] shouldBe true
                result.permissions[PermissionKeys.MESSAGE_SEND] shouldBe true
                result.permissions[PermissionKeys.MESSAGE_MANAGE] shouldBe false
                result.readPrivate shouldBe false
            }
            it("접근 권한이 없으면 다른 권한도 거부한다") {
                val result = resolve(overwrites = mapOf(1L to mapOf(PermissionKeys.MESSAGE_READ to false)))
                result.permissions[PermissionKeys.MESSAGE_READ] shouldBe false
                result.permissions[PermissionKeys.MESSAGE_SEND] shouldBe false
            }
            it("비공개 채널은 역할 덮어쓰기로 허용된 경우에만 접근을 허용한다") {
                resolve(isPrivate = true).permissions[PermissionKeys.MESSAGE_READ] shouldBe false
                val allowed = resolve(
                    isPrivate = true,
                    overwrites = mapOf(1L to mapOf(PermissionKeys.MESSAGE_READ to true)),
                )
                allowed.permissions[PermissionKeys.MESSAGE_READ] shouldBe true
                allowed.readPrivate shouldBe true
            }
            it("비공개 여부를 모르면 비공개로 판정한다") {
                resolve(isPrivate = null).permissions[PermissionKeys.MESSAGE_READ] shouldBe false
            }
            it("음성 채널은 voice_connect를 접근 권한으로 쓴다") {
                val result = resolve(channelType = "VOICE")
                result.permissions[PermissionKeys.VOICE_CONNECT] shouldBe false
                result.permissions[PermissionKeys.MESSAGE_SEND] shouldBe false
            }
            it("administrator는 비공개 채널의 모든 권한을 허용한다") {
                val result = resolve(roles = listOf(defaultRole, manager), isPrivate = true)
                result.permissions.values.toSet() shouldBe setOf(true)
                result.readPrivate shouldBe true
            }
            it("활성 멤버가 아니거나 채널이 삭제되었거나 유형을 모르면 모든 권한을 거부한다") {
                listOf(
                    resolve(roles = listOf(manager), active = false),
                    resolve(roles = listOf(manager), deleted = true),
                    resolve(roles = listOf(manager), channelType = null),
                ).forEach { result ->
                    result.permissions.values.toSet() shouldBe setOf(false)
                    result.readPrivate shouldBe false
                }
            }
        }
    })
