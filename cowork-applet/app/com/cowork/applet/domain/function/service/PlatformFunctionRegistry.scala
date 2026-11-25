package com.cowork.applet.domain.function.service

import com.cowork.applet.domain.function.model.PlatformFunction
import com.cowork.applet.domain.function.service.handler.{HttpFunctions, PlatformFunctionHandler, StorageFunctions}
import javax.inject.{Inject, Singleton}

@Singleton
class PlatformFunctionRegistry @Inject() (storageFunctions: StorageFunctions, httpFunctions: HttpFunctions):

  private val handlers: Seq[PlatformFunctionHandler] = storageFunctions.handlers ++ httpFunctions.handlers

  private val byName: Map[String, PlatformFunctionHandler] = handlers.map(h => h.definition.name -> h).toMap
  require(byName.size == handlers.size, "Platform function names must be unique.")

  def catalog: Seq[PlatformFunction] = handlers.map(_.definition)

  def find(name: String): Option[PlatformFunctionHandler] = byName.get(name)
