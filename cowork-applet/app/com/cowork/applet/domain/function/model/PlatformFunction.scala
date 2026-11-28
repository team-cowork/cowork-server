package com.cowork.applet.domain.function.model

final case class FunctionParameter(
    name: String,
    `type`: String,
    required: Boolean,
    description: String,
    allowedValues: Seq[String] = Nil
)

// permission is the app permission that a team must grant before the applet can call the function.
// Functions that touch team resources such as channels or projects declare one; the caller's own role does not count.
final case class PlatformFunction(
    name: String,
    category: String,
    description: String,
    parameters: Seq[FunctionParameter],
    returns: String,
    permission: Option[String] = None
)
