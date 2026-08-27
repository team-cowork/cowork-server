package com.cowork.channel.domain.channel.event

import com.cowork.channel.domain.channel.repository.ChannelMemberRepository
import com.cowork.channel.domain.channel.repository.ChannelRepository
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.Lock

class ChannelMembershipSyncPublisherTransactionTest :
    StringSpec({
        "channel snapshot은 source row를 비관적 쓰기 잠금한다" {
            val method = ChannelRepository::class.java.getMethod(
                "findSnapshotBatch",
                Long::class.javaPrimitiveType,
                Pageable::class.java,
            )

            method.getAnnotation(Lock::class.java).value shouldBe LockModeType.PESSIMISTIC_WRITE
        }

        "channel member snapshot은 source row를 비관적 쓰기 잠금한다" {
            val method = ChannelMemberRepository::class.java.getMethod(
                "findSnapshotByChannelId",
                Long::class.javaPrimitiveType,
            )

            method.getAnnotation(Lock::class.java).value shouldBe LockModeType.PESSIMISTIC_WRITE
        }
    })
