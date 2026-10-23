package com.cowork.project.global.logging

import ch.qos.logback.classic.pattern.ClassicConverter
import ch.qos.logback.classic.spi.ILoggingEvent

// Immediate parent package of the logger name, so Loki can group logs by subsystem without the full class name.
class ModuleNameConverter : ClassicConverter() {
    override fun convert(event: ILoggingEvent): String {
        val segments = event.loggerName.split('.')
        return if (segments.size >= 2) segments[segments.size - 2] else segments.first()
    }
}
