package com.cowork.gateway.filter

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.netty.buffer.ByteBufAllocator
import io.netty.buffer.Unpooled
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.core.io.buffer.NettyDataBufferFactory
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.test.StepVerifier

class BoundedResponseBodyTransformerTest :
    DescribeSpec({
        val bufferFactory = DefaultDataBufferFactory.sharedInstance

        fun buffer(value: String): DataBuffer = bufferFactory.wrap(value.toByteArray())

        fun readAndRelease(dataBuffer: DataBuffer): String {
            val bytes = ByteArray(dataBuffer.readableByteCount())
            dataBuffer.read(bytes)
            DataBufferUtils.release(dataBuffer)
            return bytes.decodeToString()
        }

        describe("BoundedResponseBodyTransformer는") {
            it("제한 이내 body를 완료 후 변환한다") {
                // Given
                val transformer = BoundedResponseBodyTransformer(maxBytes = 4)

                // When
                val result = transformer.transform(
                    body = Flux.just(buffer("ab"), buffer("cd")),
                ) { bytes ->
                    buffer("wrapped:${bytes.decodeToString()}")
                }

                // Then
                StepVerifier.create(result)
                    .consumeNextWith { dataBuffer -> readAndRelease(dataBuffer) shouldBe "wrapped:abcd" }
                    .verifyComplete()
            }

            it("제한을 넘으면 source 완료를 기다리지 않고 보관한 원본 buffer를 전달한다") {
                // Given
                val transformer = BoundedResponseBodyTransformer(maxBytes = 4)
                val body = Flux.concat(
                    Flux.just(buffer("ab"), buffer("cd"), buffer("ef")),
                    Mono.never(),
                )

                // When & Then
                StepVerifier.create(transformer.transform(body) { error("작은 body에서만 호출되어야 합니다") }.take(3))
                    .consumeNextWith { dataBuffer -> readAndRelease(dataBuffer) shouldBe "ab" }
                    .consumeNextWith { dataBuffer -> readAndRelease(dataBuffer) shouldBe "cd" }
                    .consumeNextWith { dataBuffer -> readAndRelease(dataBuffer) shouldBe "ef" }
                    .verifyComplete()
            }

            it("완료 전 취소되면 보관 중인 pooled buffer를 release한다") {
                // Given
                val nettyFactory = NettyDataBufferFactory(ByteBufAllocator.DEFAULT)
                val pooledBuffer = nettyFactory.wrap(Unpooled.copiedBuffer("ab".toByteArray()))
                val transformer = BoundedResponseBodyTransformer(maxBytes = 4)

                // When
                StepVerifier.create(
                    transformer.transform(
                        body = Flux.concat(Flux.just(pooledBuffer), Mono.never()),
                    ) { error("작은 body가 완료되기 전에는 호출되면 안 됩니다") },
                )
                    .thenCancel()
                    .verify()

                // Then
                pooledBuffer.nativeBuffer.refCnt() shouldBe 0
            }

            it("upstream 오류가 나면 보관 중인 pooled buffer를 release한다") {
                // Given
                val nettyFactory = NettyDataBufferFactory(ByteBufAllocator.DEFAULT)
                val pooledBuffer = nettyFactory.wrap(Unpooled.copiedBuffer("ab".toByteArray()))
                val transformer = BoundedResponseBodyTransformer(maxBytes = 4)

                // When
                StepVerifier.create(
                    transformer.transform(
                        body = Flux.concat(
                            Flux.just(pooledBuffer),
                            Mono.error(IllegalStateException("upstream failed")),
                        ),
                    ) { error("upstream 오류 시에는 호출되면 안 됩니다") },
                )
                    .expectErrorMessage("upstream failed")
                    .verify()

                // Then
                pooledBuffer.nativeBuffer.refCnt() shouldBe 0
            }
        }
    })
