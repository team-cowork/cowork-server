package com.cowork.applet.global.surreal

import com.google.inject.AbstractModule

class SurrealModule extends AbstractModule:
  override def configure(): Unit = bind(classOf[SurrealSchema]).asEagerSingleton()
