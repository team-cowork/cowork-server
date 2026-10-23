package com.cowork.roadmap.global.logging;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

// For our own code (com.cowork.<service>.domain.<feature>... or .global.<x>...), the module is the
// segment right after domain/global, so logs group by feature instead of by layer name (service/impl).
// Everything else (flatter in-house packages, third-party loggers) falls back to the logger name's
// immediate parent package.
public class ModuleNameConverter extends ClassicConverter {
    @Override
    public String convert(ILoggingEvent event) {
        String[] segments = event.getLoggerName().split("\\.");
        if (segments.length >= 5 && segments[0].equals("com") && segments[1].equals("cowork")
                && (segments[3].equals("domain") || segments[3].equals("global"))) {
            return segments[4];
        }

        return segments.length >= 2 ? segments[segments.length - 2] : segments[0];
    }
}
