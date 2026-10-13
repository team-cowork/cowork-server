package com.cowork.gateway.storage

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import org.springframework.http.HttpMethod

class StorageRelayPolicyTest :
    DescribeSpec({
        val policy = StorageRelayPolicy("cowork-bucket")
        val prefix = "/api/storage/cowork-bucket"
        val file = "12345678-1234-1234-1234-123456789abc.png"
        val signature = setOf("X-Amz-Signature")

        describe("StorageRelayPolicy의 파일 접근 정책은") {
            context("공개 팀 아이콘이면") {
                it("서명 없이 읽기를 허용한다") {
                    policy.allows(HttpMethod.GET, "$prefix/team-icons/$file", emptySet()) shouldBe true
                    policy.allows(HttpMethod.HEAD, "$prefix/team-icons/$file", emptySet()) shouldBe true
                }
                it("확정 파일의 덮어쓰기와 삭제는 거부한다") {
                    policy.allows(HttpMethod.PUT, "$prefix/team-icons/$file", signature) shouldBe false
                    policy.allows(HttpMethod.DELETE, "$prefix/team-icons/$file", signature) shouldBe false
                }
            }
            context("비공개 첨부와 프로필이면") {
                it("서명이 있는 읽기만 스토리지의 서명 검증으로 전달한다") {
                    for (key in listOf("profiles/42/$file", "chat-files/1/42/$file")) {
                        policy.allows(HttpMethod.GET, "$prefix/$key", emptySet()) shouldBe false
                        policy.allows(HttpMethod.GET, "$prefix/$key", signature) shouldBe true
                        policy.allows(HttpMethod.PUT, "$prefix/$key", signature) shouldBe false
                    }
                }
            }
            context("임시 업로드이면") {
                it("서명한 PUT만 허용하고 읽기는 거부한다") {
                    for (key in listOf(
                        "team-icon-uploads/42/$file",
                        "profile-uploads/42/$file",
                        "chat-uploads/1/42/$file",
                    )) {
                        policy.allows(HttpMethod.PUT, "$prefix/$key", signature) shouldBe true
                        policy.allows(HttpMethod.PUT, "$prefix/$key", emptySet()) shouldBe false
                        policy.allows(HttpMethod.GET, "$prefix/$key", signature) shouldBe false
                    }
                }
            }
            context("파일 접근 범위를 벗어나면") {
                it("다른 버킷과 경로 우회 및 목록 조회를 거부한다") {
                    for (path in listOf(
                        "/api/storage/other/team-icons/$file",
                        "$prefix/",
                        "$prefix/team-icons/../$file",
                        "$prefix/team-icons/%2e%2e/$file",
                        "$prefix//team-icons/$file",
                    )) {
                        policy.allows(HttpMethod.GET, path, signature) shouldBe false
                    }
                }
                it("객체 ACL과 정책 등 파일 외 S3 작업을 거부한다") {
                    for (query in listOf("acl", "policy", "uploads", "uploadId", "tagging", "list-type")) {
                        policy.allows(HttpMethod.GET, "$prefix/team-icons/$file", signature + query) shouldBe false
                    }
                }
            }
        }
    })
