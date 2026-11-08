package com.cowork.applet.global.health

import javax.inject.Inject
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}

class HealthController @Inject() (components: ControllerComponents) extends AbstractController(components):

  def ready: Action[AnyContent] = Action(Ok)
