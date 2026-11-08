package com.cowork.applet.global.swagger

import javax.inject.{Inject, Singleton}
import org.apache.pekko.util.ByteString
import play.api.Environment
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}
import scala.util.Using

@Singleton
class OpenApiController @Inject() (components: ControllerComponents, environment: Environment)
    extends AbstractController(components):

  private val specification = ByteString.fromArray(
    environment
      .resourceAsStream("public/swagger.json")
      .map(stream => Using.resource(stream)(_.readAllBytes()))
      .getOrElse(throw IllegalStateException("Package the generated swagger.json with the application."))
  )

  def document: Action[AnyContent] = Action(Ok(specification).as("application/json"))
