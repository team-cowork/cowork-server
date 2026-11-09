package com.cowork.applet.domain.placeholder.presentation.controller

import com.cowork.applet.domain.placeholder.presentation.data.response.RandomValueResDto
import com.cowork.applet.domain.placeholder.service.GenerateRandomValueService
import javax.inject.Inject
import play.api.libs.json.Json
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}

// TODO: Delete the placeholder package and its route once the business model and requirements are
// fully finalized and the first real API is implemented. It exists only because sbt-play-swagger
// fails the build when no route is documented.
class RandomValueController @Inject() (components: ControllerComponents, generateRandomValue: GenerateRandomValueService)
    extends AbstractController(components):

  def random: Action[AnyContent] = Action(Ok(Json.toJson(RandomValueResDto(generateRandomValue.execute()))))
