package com.cowork.roadmap.global.logging;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

// Immediate parent package of the logger name, so Loki can group logs by subsystem without the full class name.
public class ModuleNameConverter extends ClassicConverter {
    @Override
    public String convert(ILoggingEvent event) {
        String[] segments = event.getLoggerName().split("\\.");
        return segments.length >= 2 ? segments[segments.length - 2] : segments[0];
    }
}
