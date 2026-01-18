package com.cowork.global.logging

import org.slf4j.MDC
import org.springframework.core.task.TaskDecorator

/**
 * 비동기 작업을 넘길 때 요청 스레드의 MDC 값을 함께 넘긴다.
 */
class MdcTaskDecorator : TaskDecorator {

    override fun decorate(runnable: Runnable): Runnable {
        val context = MDC.getCopyOfContextMap()
        return Runnable {
            val previous = MDC.getCopyOfContextMap()
            if (context == null) MDC.clear() else MDC.setContextMap(context)
            try {
                runnable.run()
            } finally {
                if (previous == null) MDC.clear() else MDC.setContextMap(previous)
            }
        }
    }
}
