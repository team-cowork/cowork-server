package com.cowork.applet.domain.function.service.handler

import com.cowork.applet.domain.environment.model.AppletSecret
import com.cowork.applet.domain.environment.repository.AppletSecretRepository
import com.cowork.applet.domain.function.model.{FunctionArguments, FunctionParameter, InvocationContext, PlatformFunction}
import com.cowork.applet.domain.network.model.AppletHttpTarget
import com.cowork.applet.domain.network.repository.AppletHttpTargetRepository
import com.cowork.applet.global.crypto.SecretCipher
import com.cowork.applet.global.error.ExpectedException
import com.cowork.applet.global.outbound.{BlockedAddressException, OutboundHttpClient, ResponseTooLargeException}
import java.io.{IOException, InterruptedIOException}
import javax.inject.{Inject, Singleton}
import play.api.Logger
import play.api.http.Status.{BAD_GATEWAY, FORBIDDEN, GATEWAY_TIMEOUT, TOO_MANY_REQUESTS}
import play.api.libs.json.JsValue
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class HttpFunctions @Inject() (
    appletHttpTargetRepository: AppletHttpTargetRepository,
    appletSecretRepository: AppletSecretRepository,
    secretCipher: SecretCipher,
    outboundHttpClient: OutboundHttpClient,
    httpFetchRateLimiter: HttpFetchRateLimiter
)(using ExecutionContext):

  private val logger = Logger(getClass)

  val handlers: Seq[PlatformFunctionHandler] = Seq(FetchFunction)

  private object FetchFunction extends PlatformFunctionHandler:
    val definition: PlatformFunction = PlatformFunction(
      name = "http.fetch",
      category = "http",
      description = "소유자가 HTTP 대상으로 등록한 https 호스트에 요청합니다. 대상 설정의 시크릿 헤더와 쿼리를 서버가 넣습니다. " +
        "리다이렉트는 따라가지 않고, 응답에 있는 시크릿 값은 가립니다. 앱 하나당 분당 60회, 사용자 한 명당 분당 20회까지 호출할 수 있습니다.",
      parameters = Seq(
        FunctionParameter("url", "string", required = true, "요청할 https URL입니다. 호스트는 HTTP 대상으로 등록되어 있어야 합니다."),
        FunctionParameter("method", "string", required = false, "HTTP 메서드입니다. 기본값은 GET입니다.", HttpFetch.Methods.toSeq.sorted),
        FunctionParameter("headers", "object", required = false, "추가할 헤더입니다. 대상 설정이 정한 헤더는 보낼 수 없습니다."),
        FunctionParameter("body", "string", required = false, "요청 본문입니다. 256KB 이하여야 합니다.")
      ),
      returns = "{ status: integer, headers: object, body: string, bodyEncoding: utf8 | base64 }"
    )

    def invoke(context: InvocationContext, arguments: FunctionArguments): Future[JsValue] =
      val call = HttpFetch.parse(arguments)
      if !httpFetchRateLimiter.tryAcquire(context.appletId, context.userId) then
        throw ExpectedException(TOO_MANY_REQUESTS, "외부 요청 호출 한도를 넘었습니다. 잠시 후 다시 시도하십시오.")
      for
        target <- appletHttpTargetRepository
          .find(context.appletId, call.host)
          .map(_.getOrElse(throw ExpectedException(FORBIDDEN, "HTTP 대상으로 등록되지 않은 호스트입니다.")))
        secrets <- decryptedSecrets(target)
        request = HttpFetch.build(call, target, secrets)
        response <- outboundHttpClient.execute(request).recover(HttpFunctions.failure)
      yield
        logger.info(s"Called an external host ${call.host} for applet ${context.appletId} with status ${response.status}.")
        HttpFetch.result(response, secrets.values)

  private def decryptedSecrets(target: AppletHttpTarget): Future[Map[String, String]] =
    val names = HttpFetch.secretNames(target)
    if names.isEmpty then Future.successful(Map.empty)
    else
      appletSecretRepository.findAll(target.appletId).map { secrets =>
        secrets.collect {
          case secret if names.contains(secret.name) =>
            secret.name -> secretCipher.decrypt(secret.ciphertext, AppletSecret.associatedData(secret.appletId, secret.name))
        }.toMap
      }

object HttpFunctions:
  private val failure: PartialFunction[Throwable, Nothing] =
    case _: BlockedAddressException =>
      throw ExpectedException(FORBIDDEN, "공개 인터넷 주소로만 요청할 수 있습니다.")
    case _: ResponseTooLargeException =>
      throw ExpectedException(BAD_GATEWAY, "외부 응답이 1MB를 넘습니다.")
    case _: InterruptedIOException =>
      throw ExpectedException(GATEWAY_TIMEOUT, "외부 서비스가 제한 시간 안에 응답하지 않았습니다.")
    case _: IOException =>
      throw ExpectedException(BAD_GATEWAY, "외부 서비스에 연결하지 못했습니다.")
