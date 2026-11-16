package com.cowork.gateway.storage

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import org.springframework.http.HttpMethod

class StorageRelayPolicyTest :
    DescribeSpec({
        val policy = StorageRelayPolicy("cowork-bucket")
        val prefix = "/api/storage/cowork-bucket"
        val file = "12345678-1234-1234-1234-123456789abc.png"
        val signature = setOf("X-Amz-Algorithm", "X-Amz-Credential", "X-Amz-Signature")

        describe("StorageRelayPolicy의 파일 접근 정책은") {
            context("공개 팀 아이콘이면") {
                it("서명 없이 읽기를 허용한다") {
                    policy.allows(HttpMethod.GET, "$prefix/team-icons/$file", emptySet()) shouldBe true
                    policy.allows(HttpMethod.HEAD, "$prefix/team-icons/$file", emptySet()) shouldBe true
                }
                it("서명이 없으면 이름 목록 밖의 응답 덮어쓰기 query를 거부한다") {
                    for (query in listOf("response-content-type", "x-amz-checksum-crc64nvme")) {
                        policy.allows(HttpMethod.GET, "$prefix/team-icons/$file", setOf(query)) shouldBe false
                    }
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
                it("서명 요청이면 x-amz-와 response- 접두사 query를 대소문자와 관계없이 허용한다") {
                    val query = signature + setOf(
                        "X-Amz-Date",
                        "X-Amz-Expires",
                        "X-Amz-SignedHeaders",
                        "x-amz-checksum-mode",
                        "x-amz-checksum-crc64nvme",
                        "X-AMZ-CHECKSUM-SHA256",
                        "response-content-type",
                        "x-id",
                    )
                    for (key in listOf("profiles/42/$file", "chat-files/1/42/$file")) {
                        policy.allows(HttpMethod.GET, "$prefix/$key", query) shouldBe true
                        policy.allows(HttpMethod.HEAD, "$prefix/$key", query) shouldBe true
                    }
                }
                it("서명 query 일부만 있으면 서명 요청으로 보지 않는다") {
                    for (partial in listOf(setOf("X-Amz-Signature"), setOf("X-Amz-Algorithm", "X-Amz-Signature"))) {
                        policy.allows(HttpMethod.GET, "$prefix/chat-files/1/42/$file", partial) shouldBe false
                        policy.allows(
                            HttpMethod.GET,
                            "$prefix/team-icons/$file",
                            partial + "response-content-type",
                        ) shouldBe
                            false
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
                    for (query in listOf("acl", "policy", "uploads", "uploadId", "tagging", "list-type", "versionId")) {
                        policy.allows(HttpMethod.GET, "$prefix/team-icons/$file", signature + query) shouldBe false
                    }
                }
            }
        }
    })
