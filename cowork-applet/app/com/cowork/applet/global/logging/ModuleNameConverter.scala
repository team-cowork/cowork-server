package com.cowork.applet.global.logging

import ch.qos.logback.classic.pattern.ClassicConverter
import ch.qos.logback.classic.spi.ILoggingEvent

class ModuleNameConverter extends ClassicConverter:
  override def convert(event: ILoggingEvent): String = ModuleNameConverter.moduleOf(event.getLoggerName)

object ModuleNameConverter:
  // Own code groups by the feature after domain/global, not by layer names such as service or impl.
  def moduleOf(loggerName: String): String =
    loggerName.split('.') match
      case Array("com", "cowork", _, "domain" | "global", feature, _*) => feature
      case segments if segments.length >= 2                            => segments(segments.length - 2)
      case segments                                                    => segments(0)
