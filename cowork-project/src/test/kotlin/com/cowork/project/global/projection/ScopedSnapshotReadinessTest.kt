package com.cowork.project.global.projection

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class ScopedSnapshotReadinessTest :
    DescribeSpec({
        val stream = ProjectionStream("timeline-group", "github.timeline.state.v1", null)
        val barriers = mapOf(stream to mapOf(0 to ProjectionBarrier("topic-id", 3)))

        fun ready(nextOffset: Long, topicId: String = "topic-id", invalid: Long? = null): Boolean {
            val checkpoints = mapOf(stream to mapOf(0 to ProjectionCheckpoint(nextOffset, topicId, invalid)))
            val broker =
                mapOf(stream.topic to ProjectionTopicState("topic-id", mapOf(0 to ProjectionBrokerRange(0, 5))))
            return ProjectionCatchUpPolicy.isReady(setOf(stream), barriers, checkpoints) &&
                ProjectionCatchUpPolicy.matchesBrokerState(setOf(stream), barriers, checkpoints, broker)
        }

        it("대상별 완료를 기록하는 토픽은 현재 끝 오프셋까지 반영해야 읽을 수 있다") {
            ready(5) shouldBe true
            ready(4) shouldBe false
            ready(5, topicId = "old-topic") shouldBe false
            ready(5, invalid = 2) shouldBe false
        }
    })
