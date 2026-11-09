package com.cowork.applet.global.swagger

import javax.inject.{Inject, Singleton}
import org.apache.pekko.util.ByteString
import play.api.{Environment, Logger}
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}
import scala.util.Using

@Singleton
class OpenApiController @Inject() (components: ControllerComponents, environment: Environment)
    extends AbstractController(components):

  private val specification = environment
    .resourceAsStream("public/swagger.json")
    .map(stream => ByteString.fromArray(Using.resource(stream)(_.readAllBytes())))

  if specification.isEmpty then Logger(getClass).error("Failed to find the generated swagger.json in the application package.")

  def document: Action[AnyContent] = Action(specification.map(Ok(_).as("application/json")).getOrElse(NotFound))
