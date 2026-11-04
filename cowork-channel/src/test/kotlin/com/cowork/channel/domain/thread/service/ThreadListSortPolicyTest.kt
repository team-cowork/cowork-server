package com.cowork.channel.domain.thread.service

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import org.springframework.data.domain.Sort
import org.springframework.http.HttpStatus
import team.themoment.sdk.exception.ExpectedException

class ThreadListSortPolicyTest :
    DescribeSpec({

        describe("requireAllowed") {
            it("허용한 필드 하나로 정렬하면 통과한다") {
                shouldNotThrowAny { ThreadListSortPolicy.requireAllowed(Sort.by(Sort.Direction.DESC, "createdAt")) }
            }

            it("정렬을 생략하면 통과한다") {
                shouldNotThrowAny { ThreadListSortPolicy.requireAllowed(Sort.unsorted()) }
            }

            it("허용한 필드 여러 개로 정렬하면 통과한다") {
                shouldNotThrowAny {
                    ThreadListSortPolicy.requireAllowed(Sort.by(Sort.Order.asc("createdAt"), Sort.Order.desc("id")))
                }
            }

            it("허용하지 않은 필드로 정렬하면 BAD_REQUEST로 거부한다") {
                val error = shouldThrow<ExpectedException> {
                    ThreadListSortPolicy.requireAllowed(Sort.by("unknownField"))
                }

                error.statusCode shouldBe HttpStatus.BAD_REQUEST
                error.message shouldBe "지원하지 않는 정렬 기준입니다."
            }

            it("여러 정렬 조건 중 하나라도 허용하지 않은 필드면 거부한다") {
                val error = shouldThrow<ExpectedException> {
                    ThreadListSortPolicy.requireAllowed(
                        Sort.by(Sort.Order.asc("createdAt"), Sort.Order.desc("unknownField")),
                    )
                }

                error.statusCode shouldBe HttpStatus.BAD_REQUEST
            }
        }
    })
