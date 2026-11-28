package com.cowork.applet.global.outbound.impl

import com.cowork.applet.global.outbound.{
  BlockedAddressException,
  OutboundHttpClient,
  OutboundRequest,
  OutboundResponse,
  PublicAddresses,
  ResponseTooLargeException
}
import java.io.IOException
import java.net.{InetAddress, Proxy}
import java.time.Duration
import java.util.List as JavaList
import javax.inject.{Inject, Singleton}
import okhttp3.{Call, Callback, Dns, Headers, OkHttpClient, Request, RequestBody, Response}
import play.api.inject.ApplicationLifecycle
import scala.concurrent.{Future, Promise}
import scala.jdk.CollectionConverters.*
import scala.util.{Try, Using}

@Singleton
class OkHttpOutboundClient @Inject() (applicationLifecycle: ApplicationLifecycle) extends OutboundHttpClient:

  private val client = OkHttpClient
    .Builder()
    .dns(OkHttpOutboundClient.PublicDns)
    // Environment proxies would bypass the address check, and redirects could lead to an unchecked host.
    .proxy(Proxy.NO_PROXY)
    .followRedirects(false)
    .followSslRedirects(false)
    .retryOnConnectionFailure(false)
    // The Gateway appletCB circuit gives up after 10 seconds. A shorter call limit lets the caller receive the
    // Gateway Timeout from http.fetch instead of the Gateway fallback, and stops the outbound work at the same time.
    .connectTimeout(Duration.ofSeconds(3))
    .readTimeout(Duration.ofSeconds(7))
    .callTimeout(Duration.ofSeconds(8))
    .build()

  applicationLifecycle.addStopHook { () =>
    client.dispatcher().executorService().shutdown()
    client.connectionPool().evictAll()
    Future.unit
  }

  override def execute(request: OutboundRequest): Future[OutboundResponse] =
    val headers = Headers.Builder()
    request.headers.foreach((name, value) => headers.add(name, value))
    val body = request.body.map(bytes => RequestBody.create(bytes, null)).orNull
    val call = client.newCall(Request.Builder().url(request.url).headers(headers.build()).method(request.method, body).build())
    val promise = Promise[OutboundResponse]()
    call.enqueue(new Callback:
      override def onFailure(call: Call, error: IOException): Unit = promise.failure(error)
      override def onResponse(call: Call, response: Response): Unit =
        promise.complete(Try(Using.resource(response)(OkHttpOutboundClient.read)))
    )
    promise.future

object OkHttpOutboundClient:
  // OkHttp connects only to the addresses this lookup returns, so a second DNS answer cannot bypass the check.
  private object PublicDns extends Dns:
    override def lookup(hostname: String): JavaList[InetAddress] =
      val addresses = Dns.SYSTEM.lookup(hostname)
      if addresses.isEmpty || !addresses.asScala.forall(PublicAddresses.isPublic) then
        throw BlockedAddressException(hostname)
      addresses

  private def read(response: Response): OutboundResponse =
    val source = response.body().source()
    if source.request(OutboundHttpClient.MaxResponseBytes + 1) then throw ResponseTooLargeException()
    val headers = response.headers()
    OutboundResponse(
      status = response.code(),
      headers = headers.names().asScala.toSeq.sorted.map(name => name -> headers.values(name).asScala.mkString(", ")),
      contentType = Option(response.header("Content-Type")),
      body = source.getBuffer.readByteArray()
    )
