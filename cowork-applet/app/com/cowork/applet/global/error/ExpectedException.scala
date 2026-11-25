package com.cowork.applet.global.error

// Business failures carry the HTTP status and a Korean message that the Gateway copies into its response envelope.
final class ExpectedException(val status: Int, message: String) extends RuntimeException(message, null, false, false)
