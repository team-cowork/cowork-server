package com.cowork.team.global.projection

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class ProjectionCatchUpPolicyTest :
    DescribeSpec({
        val stream = ProjectionStream("team-group", "team-topic", "cowork-team")
        val otherStream = ProjectionStream("team-group", "other-topic", "cowork-team")
        val topicId = "topic-id"
        val barrier = ProjectionBarrier(topicId, targetOffset = 10)
        val checkpoint = ProjectionCheckpoint(nextOffset = 10, topicId = topicId, snapshotCompletedOffset = 5)
        val range = ProjectionBrokerRange(beginningOffset = 0, endOffset = 10)

        fun ready(
            checkpoints: Map<ProjectionStream, Map<Int, ProjectionCheckpoint>> =
                mapOf(stream to mapOf(0 to checkpoint)),
            barriers: Map<ProjectionStream, Map<Int, ProjectionBarrier>> = mapOf(stream to mapOf(0 to barrier)),
            requiredStreams: Set<ProjectionStream> = setOf(stream),
        ) = ProjectionCatchUpPolicy.isReady(requiredStreams, barriers, checkpoints)

        fun readyWith(value: ProjectionCheckpoint) = ready(checkpoints = mapOf(stream to mapOf(0 to value)))

        fun matches(
            checkpoints: Map<Int, ProjectionCheckpoint> = mapOf(0 to checkpoint),
            barriers: Map<Int, ProjectionBarrier> = mapOf(0 to barrier),
            topicStates: Map<String, ProjectionTopicState> =
                mapOf(stream.topic to ProjectionTopicState(topicId, mapOf(0 to range))),
        ) = ProjectionCatchUpPolicy.matchesBrokerState(
            setOf(stream),
            mapOf(stream to barriers),
            mapOf(stream to checkpoints),
            topicStates,
        )

        fun matchesWith(value: ProjectionCheckpoint) = matches(checkpoints = mapOf(0 to value))

        fun matchesRange(value: ProjectionBrokerRange) =
            matches(topicStates = mapOf(stream.topic to ProjectionTopicState(topicId, mapOf(0 to value))))

        describe("ProjectionCatchUpPolicy 객체의 isReady 메서드는") {
            context("모든 파티션 checkpoint가 snapshot 이후 barrier까지 따라잡았으면") {
                it("true를 반환한다") {
                    ready() shouldBe true
                }
            }

            context("checkpoint가 barrier를 넘어섰으면") {
                it("true를 반환한다") {
                    readyWith(checkpoint.copy(nextOffset = 11)) shouldBe true
                }
            }

            context("checkpoint가 barrier보다 뒤처졌으면") {
                it("false를 반환한다") {
                    readyWith(checkpoint.copy(nextOffset = 9)) shouldBe false
                }
            }

            context("barrier가 없으면") {
                it("false를 반환한다") {
                    ready(barriers = emptyMap()) shouldBe false
                }
            }

            context("barrier 파티션의 checkpoint가 없으면") {
                it("false를 반환한다") {
                    ready(checkpoints = emptyMap()) shouldBe false
                }
            }

            context("checkpoint와 barrier의 topicId가 다르면") {
                it("false를 반환한다") {
                    readyWith(checkpoint.copy(topicId = "recreated-topic-id")) shouldBe false
                }
            }

            context("checkpoint offset이 유효하지 않다고 표시됐으면") {
                it("false를 반환한다") {
                    readyWith(checkpoint.copy(invalidCheckpointOffset = 3)) shouldBe false
                }
            }

            context("처리할 수 없는 레코드가 표시됐으면") {
                it("false를 반환한다") {
                    readyWith(checkpoint.copy(invalidRecordOffset = 3)) shouldBe false
                }
            }

            context("snapshot이 완료되지 않았으면") {
                it("false를 반환한다") {
                    readyWith(checkpoint.copy(snapshotCompletedOffset = null)) shouldBe false
                }
            }

            context("checkpoint가 snapshot 완료 offset을 넘지 않았으면") {
                it("false를 반환한다") {
                    readyWith(checkpoint.copy(nextOffset = 10, snapshotCompletedOffset = 10)) shouldBe false
                }
            }

            context("필수 stream 중 하나라도 준비되지 않았으면") {
                it("false를 반환한다") {
                    ready(
                        barriers = mapOf(stream to mapOf(0 to barrier), otherStream to mapOf(0 to barrier)),
                        requiredStreams = setOf(stream, otherStream),
                    ) shouldBe false
                }
            }
        }

        describe("ProjectionCatchUpPolicy 객체의 matchesBrokerState 메서드는") {
            context("barrier와 checkpoint가 broker의 topic과 offset 범위에 맞으면") {
                it("true를 반환한다") {
                    matches() shouldBe true
                }
            }

            context("checkpoint가 broker offset 범위 안에서 끝에 닿지 않았으면") {
                it("true를 반환한다") {
                    matchesWith(checkpoint.copy(nextOffset = 8)) shouldBe true
                }
            }

            context("broker topic 상태가 없으면") {
                it("false를 반환한다") {
                    matches(topicStates = emptyMap()) shouldBe false
                }
            }

            context("barrier 파티션이 broker 파티션과 다르면") {
                it("false를 반환한다") {
                    matches(barriers = mapOf(0 to barrier, 1 to barrier)) shouldBe false
                }
            }

            context("checkpoint 파티션이 broker 파티션과 다르면") {
                it("false를 반환한다") {
                    matches(checkpoints = mapOf(1 to checkpoint)) shouldBe false
                }
            }

            context("barrier의 topicId가 broker와 다르면") {
                it("false를 반환한다") {
                    matches(barriers = mapOf(0 to barrier.copy(topicId = "old-topic-id"))) shouldBe false
                }
            }

            context("checkpoint의 topicId가 broker와 다르면") {
                it("false를 반환한다") {
                    matchesWith(checkpoint.copy(topicId = "old-topic-id")) shouldBe false
                }
            }

            context("snapshot 완료 offset이 없으면") {
                it("false를 반환한다") {
                    matchesWith(checkpoint.copy(snapshotCompletedOffset = null)) shouldBe false
                }
            }

            context("snapshot 완료 offset이 broker 보존 범위보다 앞이면") {
                it("false를 반환한다") {
                    matchesRange(range.copy(beginningOffset = 6)) shouldBe false
                }
            }

            context("barrier 목표 offset이 broker 끝 offset을 넘으면") {
                it("false를 반환한다") {
                    matches(barriers = mapOf(0 to barrier.copy(targetOffset = 11))) shouldBe false
                }
            }

            context("checkpoint가 broker 끝 offset을 넘으면") {
                it("false를 반환한다") {
                    matchesWith(checkpoint.copy(nextOffset = 11)) shouldBe false
                }
            }

            context("checkpoint가 broker 시작 offset보다 앞이면") {
                it("false를 반환한다") {
                    matches(
                        checkpoints = mapOf(0 to checkpoint.copy(nextOffset = 4)),
                        topicStates = mapOf(
                            stream.topic to ProjectionTopicState(topicId, mapOf(0 to range.copy(beginningOffset = 5))),
                        ),
                    ) shouldBe false
                }
            }
        }
    })
