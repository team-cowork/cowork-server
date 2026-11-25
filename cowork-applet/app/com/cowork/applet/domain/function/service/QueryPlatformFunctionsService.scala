package com.cowork.applet.domain.function.service

import com.cowork.applet.domain.function.model.PlatformFunction
import com.cowork.applet.domain.function.service.impl.QueryPlatformFunctionsServiceImpl
import com.google.inject.ImplementedBy

@ImplementedBy(classOf[QueryPlatformFunctionsServiceImpl])
trait QueryPlatformFunctionsService:
  def execute(): Seq[PlatformFunction]
