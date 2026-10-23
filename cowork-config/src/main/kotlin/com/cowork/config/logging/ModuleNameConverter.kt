package com.cowork.config.logging

import ch.qos.logback.classic.pattern.ClassicConverter
import ch.qos.logback.classic.spi.ILoggingEvent

// For our own code (com.cowork.<service>.domain.<feature>... or .global.<x>...), the module is the
// segment right after domain/global, so logs group by feature instead of by layer name (service/impl).
// Everything else (flatter in-house packages, third-party loggers) falls back to the logger name's
// immediate parent package.
class ModuleNameConverter : ClassicConverter() {
    override fun convert(event: ILoggingEvent): String {
        val segments = event.loggerName.split('.')
        if (segments.size >= 5 && segments[0] == "com" && segments[1] == "cowork" &&
            (segments[3] == "domain" || segments[3] == "global")
        ) {
            return segments[4]
        }

        return if (segments.size >= 2) segments[segments.size - 2] else segments.first()
    }
}
