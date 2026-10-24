package com.cowork.preference.domain

import com.cowork.preference.domain.ChannelRolePermissionResolver.RolePermission
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class ChannelRolePermissionResolverTest : DescribeSpec({
    describe("채널 역할 권한 합산은") {
        it("기본 역할 또는 임의 사용자 역할의 허용을 합산한다") {
            ChannelRolePermissionResolver.resolve(listOf(RolePermission(true, true, null), RolePermission(false, false, null)), "message_read", false) shouldBe true
            ChannelRolePermissionResolver.resolve(listOf(RolePermission(true, false, null), RolePermission(false, true, null)), "message_read", false) shouldBe true
        }
        it("비공개 접근은 명시 허용이 필요하고 기본 역할 덮어쓰기를 먼저 적용한다") {
            ChannelRolePermissionResolver.resolve(listOf(RolePermission(true, true, null)), "message_read", true) shouldBe false
            ChannelRolePermissionResolver.resolve(listOf(RolePermission(true, true, true)), "message_read", true) shouldBe true
            ChannelRolePermissionResolver.resolve(listOf(RolePermission(true, true, false), RolePermission(false, true, null)), "message_read", false) shouldBe false
        }
        it("여러 사용자 역할의 명시 허용이 명시 거부보다 우선한다") {
            val roles = listOf(RolePermission(true, true, false), RolePermission(false, false, false), RolePermission(false, false, true))
            ChannelRolePermissionResolver.resolve(roles, "message_read", true) shouldBe true
            ChannelRolePermissionResolver.resolve(roles.reversed(), "message_read", true) shouldBe true
        }
        it("비공개 채널에서도 접근 이외 권한은 기본 역할에서 상속한다") {
            ChannelRolePermissionResolver.resolve(listOf(RolePermission(true, true, null)), "channel_manage", true) shouldBe true
        }
        it("기본 역할의 최초 권한은 조회와 음성 연결만 허용한다") {
            TeamRolePermissions.everyone().filterValues { it }.keys shouldBe setOf("message_read", "voice_connect", "project_read")
            TeamRolePermissions.everyone().keys shouldBe PermissionKeys.ROLE_SCOPED
        }
    }
})
