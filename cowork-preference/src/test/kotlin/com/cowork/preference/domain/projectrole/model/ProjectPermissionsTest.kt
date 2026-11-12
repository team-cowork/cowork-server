package com.cowork.preference.domain.projectrole.model

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
                    ProjectPermissions(true, true, true, true, true)
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
        }
    })
