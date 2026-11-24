package com.cowork.applet.domain.function.service.impl

import com.cowork.applet.domain.function.model.PlatformFunction
import com.cowork.applet.domain.function.service.{PlatformFunctionRegistry, QueryPlatformFunctionsService}
import javax.inject.{Inject, Singleton}

@Singleton
class QueryPlatformFunctionsServiceImpl @Inject() (platformFunctionRegistry: PlatformFunctionRegistry)
    extends QueryPlatformFunctionsService:
  override def execute(): Seq[PlatformFunction] = platformFunctionRegistry.catalog
