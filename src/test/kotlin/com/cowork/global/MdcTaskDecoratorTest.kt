package com.cowork.global

import com.cowork.global.logging.MdcTaskDecorator
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import java.util.concurrent.Executors

class MdcTaskDecoratorTest {

    @AfterEach
    fun clear() = MDC.clear()

    @Test
    fun `작업을 넘긴 시점의 요청 ID를 작업 스레드에서 읽는다`() {
        MDC.put("requestId", "request-0001")
        val task = MdcTaskDecorator().decorate { assertEquals("request-0001", MDC.get("requestId")) }
        MDC.clear()

        Executors.newSingleThreadExecutor().submit(task).get()
    }

    @Test
    fun `작업이 끝나면 작업 스레드의 MDC를 원래대로 돌린다`() {
        val executor = Executors.newSingleThreadExecutor()
        MDC.put("requestId", "request-0002")
        executor.submit(MdcTaskDecorator().decorate {}).get()

        executor.submit { assertNull(MDC.get("requestId")) }.get()
    }
}
