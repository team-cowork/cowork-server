package com.cowork.applet.global.outbound

import com.cowork.applet.global.outbound.impl.OkHttpOutboundClient
import com.google.inject.ImplementedBy
import java.io.IOException
import java.net.UnknownHostException
import scala.concurrent.Future

final case class OutboundRequest(method: String, url: String, headers: Seq[(String, String)], body: Option[Array[Byte]])

final case class OutboundResponse(status: Int, headers: Seq[(String, String)], contentType: Option[String], body: Array[Byte])

// The host resolved to an address that PublicAddresses rejects.
final class BlockedAddressException(host: String) extends UnknownHostException(s"Blocked a non-public address for $host.")

final class ResponseTooLargeException extends IOException("Exceeded the outbound response size limit.")

@ImplementedBy(classOf[OkHttpOutboundClient])
trait OutboundHttpClient:
  def execute(request: OutboundRequest): Future[OutboundResponse]

object OutboundHttpClient:
  val MaxResponseBytes: Long = 1024L * 1024
