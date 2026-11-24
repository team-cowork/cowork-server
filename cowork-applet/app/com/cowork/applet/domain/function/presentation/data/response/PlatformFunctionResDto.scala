package com.cowork.applet.domain.function.presentation.data.response

import com.cowork.applet.domain.function.model.{FunctionParameter, PlatformFunction}
import play.api.libs.json.{Json, OWrites}

final case class FunctionParameterResDto(
    name: String,
    `type`: String,
    required: Boolean,
    description: String,
    allowedValues: Seq[String]
)

object FunctionParameterResDto:
  given OWrites[FunctionParameterResDto] = Json.writes[FunctionParameterResDto]

  def from(parameter: FunctionParameter): FunctionParameterResDto = FunctionParameterResDto(
    parameter.name,
    parameter.`type`,
    parameter.required,
    parameter.description,
    parameter.allowedValues
  )

final case class PlatformFunctionResDto(
    name: String,
    category: String,
    description: String,
    parameters: Seq[FunctionParameterResDto],
    returns: String
)

object PlatformFunctionResDto:
  given OWrites[PlatformFunctionResDto] = Json.writes[PlatformFunctionResDto]

  def from(function: PlatformFunction): PlatformFunctionResDto = PlatformFunctionResDto(
    function.name,
    function.category,
    function.description,
    function.parameters.map(FunctionParameterResDto.from),
    function.returns
  )
