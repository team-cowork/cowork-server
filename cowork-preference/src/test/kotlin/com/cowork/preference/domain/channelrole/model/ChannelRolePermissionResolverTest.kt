package com.cowork.preference.domain.channelrole.model

import com.cowork.preference.domain.access.permission.PermissionDecisionReason
import com.cowork.preference.domain.access.permission.PermissionKeys
import com.cowork.preference.domain.access.permission.PermissionSource
import com.cowork.preference.domain.access.permission.PermissionSourceKind
import com.cowork.preference.domain.access.permission.PolicyRole
import com.cowork.preference.domain.channelrole.model.ChannelRolePermissionResolver.RolePermission
import com.cowork.preference.domain.teamrole.model.TeamRolePermissions
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class ChannelRolePermissionResolverTest :
    DescribeSpec({
        describe("채널 역할 권한 합산은") {
            it("기본 역할 또는 임의 사용자 역할의 허용을 합산한다") {
                ChannelRolePermissionResolver.resolve(
                    listOf(RolePermission(true, true, null), RolePermission(false, false, null)),
                    "message_read",
                    false,
                ) shouldBe
                    true
                ChannelRolePermissionResolver.resolve(
                    listOf(RolePermission(true, false, null), RolePermission(false, true, null)),
                    "message_read",
                    false,
                ) shouldBe
                    true
            }
            it("비공개 접근은 명시 허용이 필요하고 기본 역할 덮어쓰기를 먼저 적용한다") {
                ChannelRolePermissionResolver.resolve(
                    listOf(RolePermission(true, true, null)),
                    "message_read",
                    true,
                ) shouldBe
                    false
                ChannelRolePermissionResolver.resolve(
                    listOf(RolePermission(true, true, true)),
                    "message_read",
                    true,
                ) shouldBe
                    true
                ChannelRolePermissionResolver.resolve(
                    listOf(RolePermission(true, true, false), RolePermission(false, true, null)),
                    "message_read",
                    false,
                ) shouldBe
                    false
            }
            it("여러 사용자 역할의 명시 허용이 명시 거부보다 우선한다") {
                val roles =
                    listOf(
                        RolePermission(true, true, false),
                        RolePermission(false, false, false),
                        RolePermission(false, false, true),
                    )
                ChannelRolePermissionResolver.resolve(roles, "message_read", true) shouldBe true
                ChannelRolePermissionResolver.resolve(roles.reversed(), "message_read", true) shouldBe true
            }
            it("비공개 채널에서도 접근 이외 권한은 기본 역할에서 상속한다") {
                ChannelRolePermissionResolver.resolve(
                    listOf(RolePermission(true, true, null)),
                    "channel_manage",
                    true,
                ) shouldBe
                    true
            }
            it("기본 역할의 최초 권한은 조회, 음성 연결, 일정 만들기와 앱 사용만 허용한다") {
                TeamRolePermissions.everyone().filterValues { it }.keys shouldBe
                    setOf(
                        "message_read",
                        "voice_connect",
                        "project_read",
                        "project_board_read",
                        "calendar_event_create",
                        "use_applets",
                    )
                TeamRolePermissions.everyone().keys shouldBe PermissionKeys.ROLE_SCOPED
            }
        }

        describe("채널 역할 권한 판정 근거는") {
            fun explain(roles: List<RolePermission>, permission: String = "message_read", isPrivate: Boolean = false) =
                ChannelRolePermissionResolver.explain(roles, permission, isPrivate)

            it("판정 순서에 따라 마지막으로 결과를 정한 규칙을 반환한다") {
                explain(listOf(RolePermission(true, true, null))).decidedBy shouldBe PermissionDecisionReason.ROLE_GRANT
                explain(listOf(RolePermission(true, false, null))).decidedBy shouldBe PermissionDecisionReason.NO_GRANT
                explain(listOf(RolePermission(true, true, null)), isPrivate = true).decidedBy shouldBe
                    PermissionDecisionReason.PRIVATE_CHANNEL
                explain(listOf(RolePermission(true, true, false))).decidedBy shouldBe
                    PermissionDecisionReason.DEFAULT_OVERWRITE
                explain(listOf(RolePermission(true, true, null), RolePermission(false, true, false))).decidedBy shouldBe
                    PermissionDecisionReason.ROLE_OVERWRITE_DENY
                explain(
                    listOf(RolePermission(false, false, false), RolePermission(false, false, true)),
                ).decidedBy shouldBe PermissionDecisionReason.ROLE_OVERWRITE_ALLOW
            }
            it("채널 권한은 관리자 역할이 덮어쓰기보다 우선한다") {
                val decision = explain(listOf(RolePermission(false, false, false, administrator = true, roleId = 9L)))
                decision.allowed shouldBe true
                decision.decidedBy shouldBe PermissionDecisionReason.ADMINISTRATOR
            }
            it("역할 값과 덮어쓰기 값을 기본 역할과 사용자 역할로 구분해 근거에 담는다") {
                val decision = explain(
                    listOf(
                        RolePermission(true, true, false, roleId = 1L, roleName = "@everyone"),
                        RolePermission(false, false, null, roleId = 2L, roleName = "Guest"),
                    ),
                )
                decision.sources shouldBe listOf(
                    PermissionSource(PermissionSourceKind.TEAM_ROLE, "message_read", true, 1L, "@everyone"),
                    PermissionSource(
                        PermissionSourceKind.CHANNEL_DEFAULT_OVERWRITE,
                        "message_read",
                        false,
                        1L,
                        "@everyone",
                    ),
                    PermissionSource(PermissionSourceKind.TEAM_ROLE, "message_read", false, 2L, "Guest"),
                )
            }
            it("역할 전체 권한에서 canAccess 쿼리와 같은 판정 입력을 만든다") {
                val role = PolicyRole(3L, "Mod", mapOf("administrator" to false, "message_send" to true))
                ChannelRolePermissionResolver.rolePermission(
                    role,
                    mapOf("message_send" to false),
                    "message_send",
                ) shouldBe
                    RolePermission(false, true, false, false, 3L, "Mod")
                ChannelRolePermissionResolver.rolePermission(role, null, "message_pin") shouldBe
                    RolePermission(false, false, null, false, 3L, "Mod")
            }
        }

        describe("채널 역할 권한의 resolve는") {
            it("판정 근거 없이 explain과 같은 결과를 낸다") {
                val roleSets = listOf(
                    listOf(RolePermission(true, true, null)),
                    listOf(RolePermission(true, true, false), RolePermission(false, false, true)),
                    listOf(
                        RolePermission(true, false, null),
                        RolePermission(false, false, false, administrator = true),
                    ),
                )
                roleSets.forEach { roles ->
                    listOf("message_read", "message_send", "manage_team").forEach { permission ->
                        listOf(true, false).forEach { isPrivate ->
                            ChannelRolePermissionResolver.resolve(roles, permission, isPrivate) shouldBe
                                ChannelRolePermissionResolver.explain(roles, permission, isPrivate).allowed
                        }
                    }
                }
            }
        }
    })
