package com.cowork.applet.domain.function.service

import com.cowork.applet.domain.function.model.PlatformFunction
import com.cowork.applet.domain.function.service.handler.{HttpFunctions, PlatformFunctionHandler, StorageFunctions}
import javax.inject.{Inject, Singleton}

@Singleton
class PlatformFunctionRegistry(handlers: Seq[PlatformFunctionHandler]):

  @Inject() def this(storageFunctions: StorageFunctions, httpFunctions: HttpFunctions) =
    this(storageFunctions.handlers ++ httpFunctions.handlers)

  private val byName: Map[String, PlatformFunctionHandler] = handlers.map(h => h.definition.name -> h).toMap
  require(byName.size == handlers.size, "Platform function names must be unique.")

  def catalog: Seq[PlatformFunction] = handlers.map(_.definition)

  // A version can request only the app permissions that some function uses.
  val permissions: Set[String] = handlers.flatMap(_.definition.permission).toSet

  def find(name: String): Option[PlatformFunctionHandler] = byName.get(name)
