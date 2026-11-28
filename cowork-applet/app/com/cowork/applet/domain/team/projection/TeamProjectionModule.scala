package com.cowork.applet.domain.team.projection

import com.google.inject.AbstractModule

class TeamProjectionModule extends AbstractModule:
  override def configure(): Unit = bind(classOf[TeamProjections]).asEagerSingleton()
