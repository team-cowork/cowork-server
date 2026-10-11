package com.cowork.preference.service

import com.cowork.preference.security.RequesterContext
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec

class AccountOwnershipGuardTest :
    DescribeSpec({
        describe("AccountOwnershipGuard의 계정 설정 조회·수정 권한은") {
            context("일반 사용자의 본인 계정이면") {
                it("접근을 허용한다") {
                    val requester = RequesterContext(userId = 10L, role = "MEMBER")

                    shouldNotThrowAny {
                        AccountOwnershipGuard.requireOwner(10L, requester)
                    }
                }
            }

            context("일반 사용자가 타인 계정을 요청하면") {
                it("접근을 거부한다") {
                    val requester = RequesterContext(userId = 10L, role = "MEMBER")

                    shouldThrow<AccountOwnershipDeniedException> {
                        AccountOwnershipGuard.requireOwner(20L, requester)
                    }
                }
            }

            context("전역 ADMIN의 본인 계정이면") {
                it("접근을 허용한다") {
                    val requester = RequesterContext(userId = 10L, role = "ADMIN")

                    shouldNotThrowAny {
                        AccountOwnershipGuard.requireOwner(10L, requester)
                    }
                }
            }

            context("전역 ADMIN이 타인 계정을 요청하면") {
                it("역할에 따른 우회 없이 접근을 거부한다") {
                    val requester = RequesterContext(userId = 10L, role = "ADMIN")

                    shouldThrow<AccountOwnershipDeniedException> {
                        AccountOwnershipGuard.requireOwner(20L, requester)
                    }
                }
            }
        }
    })
