package com.cowork.channel.domain.channel.event

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.springframework.transaction.annotation.Transactional

class ChannelMembershipSyncPublisherTransactionTest :
    StringSpec({
        "acquires the startup lock outside a read-only transaction" {
            val method = ChannelMembershipSyncPublisher::class.java.getDeclaredMethod("publishAllSnapshots")

            method.getAnnotation(Transactional::class.java) shouldBe null
        }
    })
