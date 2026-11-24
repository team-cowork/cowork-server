package com.cowork.applet.domain.function.model

final case class FunctionParameter(
    name: String,
    `type`: String,
    required: Boolean,
    description: String,
    allowedValues: Seq[String] = Nil
)

final case class PlatformFunction(
    name: String,
    category: String,
    description: String,
    parameters: Seq[FunctionParameter],
    returns: String
)
