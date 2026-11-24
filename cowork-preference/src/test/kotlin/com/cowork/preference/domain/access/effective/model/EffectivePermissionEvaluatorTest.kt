package com.cowork.preference.domain.access.effective.model

import com.cowork.preference.domain.access.permission.PermissionDecisionReason
import com.cowork.preference.domain.access.permission.PermissionKeys
import com.cowork.preference.domain.access.permission.PolicyRole
import com.cowork.preference.domain.channelrole.model.ChannelRolePermissionResolver
import com.cowork.preference.domain.projectrole.model.ProjectPermissions
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class EffectivePermissionEvaluatorTest :
    DescribeSpec({
        val everyone =
            PolicyRole(1L, "@everyone", mapOf("message_read" to true, "message_send" to true), isDefault = true)
        val guest = PolicyRole(2L, "Guest", mapOf("message_send" to false))

        describe("EffectivePermissionEvaluator 클래스의 channel 메서드는") {
            it("역할 ID에 맞는 채널 덮어쓰기를 적용한다") {
                val decisions = EffectivePermissionEvaluator.channel(
                    listOf(everyone, guest),
                    mapOf(2L to mapOf("message_send" to false)),
                    isPrivate = false,
                    accessPermission = "message_read",
                    keys = listOf("message_read", "message_send"),
                )
                decisions.mapValues { it.value.allowed } shouldBe mapOf("message_read" to true, "message_send" to false)
                decisions.getValue("message_send").decidedBy shouldBe PermissionDecisionReason.ROLE_OVERWRITE_DENY
            }
            it("Channel 서비스와 같이 접근 권한과 해당 권한을 모두 허용할 때만 허용한다") {
                val roles = listOf(everyone, guest, PolicyRole(3L, "Mod", mapOf("message_pin" to true)))
                val overwrites = mapOf(1L to mapOf("message_read" to false), 3L to mapOf("message_read" to true))
                listOf(true, false).forEach { isPrivate ->
                    val decisions = EffectivePermissionEvaluator.channel(
                        roles,
                        overwrites,
                        isPrivate,
                        "message_read",
                        PermissionKeys.CHANNEL_SCOPED.toList(),
                    )
                    fun resolve(key: String) = ChannelRolePermissionResolver.resolve(
                        roles.map { ChannelRolePermissionResolver.rolePermission(it, overwrites[it.roleId], key) },
                        key,
                        isPrivate,
                    )
                    decisions.forEach { (key, decision) ->
                        decision.allowed shouldBe (resolve("message_read") && resolve(key))
                    }
                }
            }
            it("접근 권한이 없으면 허용된 다른 권한을 REQUIRES_CHANNEL_ACCESS로 거부한다") {
                val decisions = EffectivePermissionEvaluator.channel(
                    listOf(everyone),
                    mapOf(1L to mapOf("message_read" to false)),
                    isPrivate = false,
                    accessPermission = "message_read",
                    keys = listOf("message_send"),
                )
                decisions.getValue("message_send").allowed shouldBe false
                decisions.getValue("message_send").decidedBy shouldBe PermissionDecisionReason.REQUIRES_CHANNEL_ACCESS
            }
            it("음성 채널의 채팅 작업과 음성 로그 조회는 message_read도 요구한다") {
                val member = PolicyRole(
                    1L,
                    "@everyone",
                    mapOf(
                        "voice_connect" to true,
                        "voice_speak" to true,
                        "message_send" to true,
                        "voice_log_read" to true,
                    ),
                    isDefault = true,
                )
                val decisions = EffectivePermissionEvaluator.channel(
                    listOf(member),
                    emptyMap(),
                    isPrivate = false,
                    accessPermission = "voice_connect",
                    keys = listOf("voice_speak", "message_send", "voice_log_read"),
                )
                decisions.getValue("voice_speak").allowed shouldBe true
                decisions.getValue("message_send").decidedBy shouldBe PermissionDecisionReason.REQUIRES_MESSAGE_READ
                decisions.getValue("voice_log_read").decidedBy shouldBe PermissionDecisionReason.REQUIRES_MESSAGE_READ
            }
            it("음성 채널은 voice_connect를 접근 권한으로 쓴다") {
                val speaker = PolicyRole(1L, "@everyone", mapOf("voice_speak" to true), isDefault = true)
                val decisions = EffectivePermissionEvaluator.channel(
                    listOf(speaker),
                    emptyMap(),
                    isPrivate = false,
                    accessPermission = "voice_connect",
                    keys = listOf("voice_speak"),
                )
                decisions.getValue("voice_speak").decidedBy shouldBe PermissionDecisionReason.REQUIRES_CHANNEL_ACCESS
            }
        }

        describe("EffectivePermissionEvaluator 클래스의 project 메서드는") {
            val reader = PolicyRole(1L, "@everyone", mapOf("project_read" to true), isDefault = true)

            it("저장된 접근 상태와 다시 계산한 판정이 같으면 판정을 그대로 반환한다") {
                val decision = EffectivePermissionEvaluator.project(
                    listOf(reader),
                    emptyList(),
                    isPrivate = false,
                    stored = ProjectPermissions(read = true),
                    keys = listOf("project_read"),
                ).getValue("project_read")
                decision.allowed shouldBe true
                decision.decidedBy shouldBe PermissionDecisionReason.ROLE_GRANT
            }
            it("저장된 접근 상태가 다르면 저장된 값을 따르고 ACCESS_STATE_PENDING으로 표시한다") {
                val decision = EffectivePermissionEvaluator.project(
                    listOf(reader),
                    emptyList(),
                    isPrivate = false,
                    stored = ProjectPermissions(),
                    keys = listOf("project_read"),
                ).getValue("project_read")
                decision.allowed shouldBe false
                decision.decidedBy shouldBe PermissionDecisionReason.ACCESS_STATE_PENDING
            }
            it("팀 역할에 관리자 권한이 있으면 관리자로 판정한다") {
                val admin = PolicyRole(9L, "Admin", mapOf("administrator" to true))
                EffectivePermissionEvaluator.project(
                    listOf(admin),
                    emptyList(),
                    isPrivate = true,
                    stored = ProjectPermissions(true, true, true, true, true, true, true),
                    keys = PermissionKeys.PROJECT_SCOPED.toList(),
                ).values.map { it.decidedBy }.distinct() shouldBe listOf(PermissionDecisionReason.ADMINISTRATOR)
            }
        }
    })
