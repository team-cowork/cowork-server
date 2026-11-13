package com.cowork.preference.global.coroutine

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.coroutines.CancellationException

class CancellableResultTest :
    DescribeSpec({
        describe("runCatchingCancellable 함수는") {
            context("블록이 값을 반환하면") {
                it("성공 Result를 반환한다") {
                    runCatchingCancellable { 42 }.getOrNull() shouldBe 42
                }
            }

            context("블록이 일반 예외를 던지면") {
                it("실패 Result로 감싼다") {
                    val failure = IllegalStateException("boom")

                    val result = runCatchingCancellable<Int> { throw failure }

                    result.exceptionOrNull() shouldBeSameInstanceAs failure
                }
            }

            context("블록이 CancellationException을 던지면") {
                it("Result로 감싸지 않고 다시 던진다") {
                    val cancellation = CancellationException("cancelled")

                    val thrown = shouldThrow<CancellationException> {
                        runCatchingCancellable<Int> { throw cancellation }
                    }

                    thrown shouldBeSameInstanceAs cancellation
                }
            }
        }
    })
