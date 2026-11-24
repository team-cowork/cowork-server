package com.cowork.preference.domain.projectrole.model

import com.cowork.preference.domain.access.permission.PermissionDecisionReason
import com.cowork.preference.domain.access.permission.PermissionSource
import com.cowork.preference.domain.access.permission.PermissionSourceKind
import com.cowork.preference.domain.access.permission.PolicyRole
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.vertx.core.json.JsonObject

class ProjectPermissionsTest :
    DescribeSpec({
        describe("프로젝트 권한 상속") {
            val defaultRole = JsonObject().put("project_read", true)
            it("기본 역할을 상속하여 공개 프로젝트를 조회한다") {
                ProjectPermissions.resolve(listOf(defaultRole), emptyList()) shouldBe ProjectPermissions(read = true)
            }
            it("모든 팀 역할의 허용을 합치고 false로 다른 역할의 허용을 취소하지 않는다") {
                val writer = JsonObject().put("project_write", true)
                val reader = JsonObject().put("project_write", false)
                ProjectPermissions.resolve(listOf(defaultRole, writer, reader), emptyList()) shouldBe
                    ProjectPermissions(read = true, write = true)
            }
            it("프로젝트 거부는 상속을 덮어쓰고 명시적 허용은 다른 프로젝트 역할의 거부보다 우선한다") {
                val deny = JsonObject().put("read", false)
                val allow = JsonObject().put("read", true)
                ProjectPermissions.resolve(listOf(defaultRole), listOf(deny)) shouldBe ProjectPermissions()
                ProjectPermissions.resolve(listOf(defaultRole), listOf(deny, allow)) shouldBe
                    ProjectPermissions(read = true)
            }
            it("비공개 프로젝트는 기본 역할의 읽기로 공개되지 않는다") {
                ProjectPermissions.resolve(listOf(defaultRole), emptyList(), isPrivate = true) shouldBe
                    ProjectPermissions()
                ProjectPermissions.resolve(
                    listOf(defaultRole),
                    listOf(JsonObject().put("read", true)),
                    isPrivate = true,
                ) shouldBe
                    ProjectPermissions(read = true)
            }
            it("명시적 읽기 역할을 회수하면 공개 권한이 같아도 비공개 접근 판정은 달라진다") {
                val teamRoles = listOf(defaultRole.copy().put("project_manage", true))
                val explicitRead = listOf(JsonObject().put("read", true))

                ProjectPermissions.resolve(teamRoles, explicitRead) shouldBe
                    ProjectPermissions.resolve(teamRoles, emptyList())
                ProjectPermissions.resolve(teamRoles, explicitRead, isPrivate = true).read shouldBe true
                ProjectPermissions.resolve(teamRoles, emptyList(), isPrivate = true).read shouldBe false
            }
            it("쓰기 관리 재정렬은 읽기가 없으면 허용되지 않는다") {
                val overrides = JsonObject().put("write", true).put("manage", true).put("reorder", true)
                    .put("channel_archive", true)
                ProjectPermissions.resolve(emptyList(), listOf(overrides)) shouldBe ProjectPermissions()
            }
            it("관리와 재정렬은 쓰기 권한을 부여하지 않는다") {
                val role = defaultRole.copy().put("project_manage", true).put("project_reorder", true)
                ProjectPermissions.resolve(listOf(role), emptyList()) shouldBe
                    ProjectPermissions(read = true, manage = true, reorder = true)
            }
            it("확인된 팀 관리자는 비공개 프로젝트의 정책을 우회한다") {
                ProjectPermissions.resolve(
                    emptyList(),
                    listOf(JsonObject().put("read", false)),
                    manager = true,
                    isPrivate = true,
                ) shouldBe
                    ProjectPermissions(true, true, true, true, true, true, true)
            }
            it("채널 보관은 팀 기본 키를 상속하고 프로젝트 역할로 프로젝트마다 덮어쓴다") {
                val archiver = defaultRole.copy().put("project_channel_archive", true)
                ProjectPermissions.resolve(listOf(archiver), emptyList()).channelArchive shouldBe true
                ProjectPermissions.resolve(
                    listOf(archiver),
                    listOf(JsonObject().put("channel_archive", false)),
                ).channelArchive shouldBe false
                ProjectPermissions.resolve(
                    listOf(defaultRole),
                    listOf(JsonObject().put("channel_archive", true)),
                ).channelArchive shouldBe true
            }
            it("채널 보관은 관리 권한을 부여하지 않는다") {
                val archiver = defaultRole.copy().put("project_channel_archive", true)
                ProjectPermissions.resolve(listOf(archiver), emptyList()) shouldBe
                    ProjectPermissions(read = true, channelArchive = true)
            }
            it("보드 보기는 팀 기본 키를 상속하고 프로젝트 역할로 프로젝트마다 덮어쓴다") {
                val boardReader = defaultRole.copy().put("project_board_read", true)
                ProjectPermissions.resolve(listOf(boardReader), emptyList()) shouldBe
                    ProjectPermissions(read = true, boardRead = true)
                ProjectPermissions.resolve(
                    listOf(boardReader),
                    listOf(JsonObject().put("board_read", false)),
                ).boardRead shouldBe false
                ProjectPermissions.resolve(
                    listOf(defaultRole),
                    listOf(JsonObject().put("board_read", true)),
                ).boardRead shouldBe true
            }
            it("보드 수정은 보드 보기가 없으면 허용되지 않는다") {
                val boardWriter = defaultRole.copy().put("project_board_write", true)
                ProjectPermissions.resolve(listOf(boardWriter), emptyList()) shouldBe
                    ProjectPermissions(read = true)
                ProjectPermissions.resolve(
                    listOf(boardWriter.copy().put("project_board_read", true)),
                    listOf(JsonObject().put("board_read", false)),
                ) shouldBe ProjectPermissions(read = true)
            }
            it("보드 권한은 프로젝트 읽기가 없으면 허용되지 않는다") {
                val overrides = JsonObject().put("board_read", true).put("board_write", true)
                ProjectPermissions.resolve(emptyList(), listOf(overrides)) shouldBe ProjectPermissions()
            }
            it("보드 수정은 프로젝트 쓰기 권한을 부여하지 않는다") {
                val role = defaultRole.copy().put("project_board_read", true).put("project_board_write", true)
                ProjectPermissions.resolve(listOf(role), emptyList()) shouldBe
                    ProjectPermissions(read = true, boardRead = true, boardWrite = true)
            }
        }

        describe("프로젝트 권한 판정 근거는") {
            val reader =
                PolicyRole(1L, "@everyone", mapOf("project_read" to true, "project_write" to true), isDefault = true)
            fun explain(
                projectRoles: List<PolicyRole> = emptyList(),
                manager: Boolean = false,
                isPrivate: Boolean = false,
                teamRoles: List<PolicyRole> = listOf(reader),
            ) = ProjectPermissions.explain(teamRoles, projectRoles, manager, isPrivate)

            it("모든 프로젝트 권한 키를 정의 순서대로 반환한다") {
                explain().keys.toList() shouldBe ProjectPermissions.ROLE_KEYS.map { "project_$it" }
            }
            it("프로젝트 역할의 명시 값을 팀 역할보다 우선해 근거와 함께 반환한다") {
                val deny = PolicyRole(null, "blocked", mapOf("write" to false))
                val decision = explain(listOf(deny)).getValue("project_write")
                decision.allowed shouldBe false
                decision.decidedBy shouldBe PermissionDecisionReason.PROJECT_OVERRIDE_DENY
                decision.sources shouldBe listOf(
                    PermissionSource(PermissionSourceKind.TEAM_ROLE, "project_write", true, 1L, "@everyone"),
                    PermissionSource(PermissionSourceKind.PROJECT_ROLE, "write", false, null, "blocked"),
                )
            }
            it("비공개 프로젝트는 프로젝트 역할의 읽기 허용이 없으면 PRIVATE_PROJECT로 거부한다") {
                explain(isPrivate = true).getValue("project_read").decidedBy shouldBe
                    PermissionDecisionReason.PRIVATE_PROJECT
            }
            it("읽기 권한이 없으면 다른 허용 권한을 REQUIRES_PROJECT_READ로 거부한다") {
                val decisions = explain(isPrivate = true)
                decisions.getValue("project_write").allowed shouldBe false
                decisions.getValue("project_write").decidedBy shouldBe PermissionDecisionReason.REQUIRES_PROJECT_READ
                decisions.getValue("project_manage").decidedBy shouldBe PermissionDecisionReason.NO_GRANT
            }
            it("보드 보기 권한이 없으면 보드 수정을 REQUIRES_BOARD_READ로 거부한다") {
                val writer = PolicyRole(2L, "Writer", mapOf("project_read" to true, "project_board_write" to true))
                explain(teamRoles = listOf(writer)).getValue("project_board_write").decidedBy shouldBe
                    PermissionDecisionReason.REQUIRES_BOARD_READ
            }
            it("관리자는 모든 권한을 ADMINISTRATOR로 허용한다") {
                val admin = PolicyRole(3L, "Admin", mapOf("administrator" to true))
                explain(listOf(PolicyRole(null, "blocked", mapOf("read" to false))), true, true, listOf(admin))
                    .values.map { it.allowed to it.decidedBy }.distinct() shouldBe
                    listOf(true to PermissionDecisionReason.ADMINISTRATOR)
            }
        }
    })
