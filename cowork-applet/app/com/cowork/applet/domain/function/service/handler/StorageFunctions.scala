package com.cowork.applet.domain.function.service.handler

import com.cowork.applet.domain.function.model.{FunctionArguments, FunctionParameter, InvocationContext, PlatformFunction}
import com.cowork.applet.domain.storage.model.{StorageEntry, StorageScope}
import com.cowork.applet.domain.storage.repository.AppletStorageRepository
import com.cowork.applet.domain.storage.service.StorageLimits
import java.time.Instant
import javax.inject.{Inject, Singleton}
import play.api.libs.json.{JsNull, JsValue, Json}
import scala.concurrent.{ExecutionContext, Future}

// Key-value storage is the free database tier. Applets that need more connect their own database later
// through the secret-aware HTTP function.
@Singleton
class StorageFunctions @Inject() (appletStorageRepository: AppletStorageRepository)(using ExecutionContext):

  val handlers: Seq[PlatformFunctionHandler] = Seq(GetFunction, SetFunction, DeleteFunction, ListFunction)

  private object GetFunction extends PlatformFunctionHandler:
    val definition: PlatformFunction = PlatformFunction(
      name = "storage.get",
      category = "storage",
      description = "키에 저장된 값을 조회합니다. 값이 없으면 null을 반환합니다.",
      parameters = Seq(StorageFunctions.KeyParameter, StorageFunctions.ScopeParameter),
      returns = "{ value: JSON | null }"
    )

    def invoke(context: InvocationContext, arguments: FunctionArguments): Future[JsValue] =
      val scope = StorageFunctions.scope(arguments)
      val key = StorageLimits.key(arguments.required[String]("key"))
      appletStorageRepository
        .find(context.appletId, scope, StorageEntry.ownerOf(scope, context.userId), key)
        .map(entry => Json.obj("value" -> entry.map(_.value).getOrElse[JsValue](JsNull)))

  private object SetFunction extends PlatformFunctionHandler:
    val definition: PlatformFunction = PlatformFunction(
      name = "storage.set",
      category = "storage",
      description = "키에 JSON 값을 저장합니다. 같은 키가 있으면 값을 바꿉니다.",
      parameters = Seq(
        StorageFunctions.KeyParameter,
        FunctionParameter("value", "json", required = true, "저장할 JSON 값입니다. 16KB 이하여야 합니다."),
        StorageFunctions.ScopeParameter
      ),
      returns = "{ updatedAt: string }"
    )

    def invoke(context: InvocationContext, arguments: FunctionArguments): Future[JsValue] =
      val scope = StorageFunctions.scope(arguments)
      val key = StorageLimits.key(arguments.required[String]("key"))
      val value = StorageLimits.value(arguments.requiredJson("value"))
      val ownerId = StorageEntry.ownerOf(scope, context.userId)
      // The capacity check and the write are separate queries, so concurrent writes can pass the limit slightly.
      for
        existing <- appletStorageRepository.find(context.appletId, scope, ownerId, key)
        count <- if existing.isDefined then Future.successful(0) else appletStorageRepository.count(context.appletId)
        _ = StorageLimits.requireCapacity(existing.isDefined, count)
        entry = StorageEntry(context.appletId, scope, ownerId, key, value, Instant.now())
        _ <- appletStorageRepository.upsert(entry)
      yield Json.obj("updatedAt" -> entry.updatedAt)

  private object DeleteFunction extends PlatformFunctionHandler:
    val definition: PlatformFunction = PlatformFunction(
      name = "storage.delete",
      category = "storage",
      description = "키에 저장된 값을 삭제합니다.",
      parameters = Seq(StorageFunctions.KeyParameter, StorageFunctions.ScopeParameter),
      returns = "{ deleted: boolean }"
    )

    def invoke(context: InvocationContext, arguments: FunctionArguments): Future[JsValue] =
      val scope = StorageFunctions.scope(arguments)
      val key = StorageLimits.key(arguments.required[String]("key"))
      appletStorageRepository
        .delete(context.appletId, scope, StorageEntry.ownerOf(scope, context.userId), key)
        .map(deleted => Json.obj("deleted" -> deleted))

  private object ListFunction extends PlatformFunctionHandler:
    val definition: PlatformFunction = PlatformFunction(
      name = "storage.list",
      category = "storage",
      description = "접두사로 시작하는 키와 값을 키 순서로 조회합니다.",
      parameters = Seq(
        FunctionParameter("prefix", "string", required = false, "조회할 키의 접두사입니다. 생략하면 전체를 조회합니다."),
        FunctionParameter("limit", "integer", required = false, "조회 개수입니다. 1 이상 100 이하이며 기본값은 50입니다."),
        StorageFunctions.ScopeParameter
      ),
      returns = "{ entries: [{ key: string, value: JSON, updatedAt: string }] }"
    )

    def invoke(context: InvocationContext, arguments: FunctionArguments): Future[JsValue] =
      val scope = StorageFunctions.scope(arguments)
      val prefix = StorageLimits.prefix(arguments.optional[String]("prefix").getOrElse(""))
      val limit = StorageLimits.listLimit(arguments.optional[Int]("limit"))
      appletStorageRepository
        .list(context.appletId, scope, StorageEntry.ownerOf(scope, context.userId), prefix, limit)
        .map(entries =>
          Json.obj("entries" -> entries.map(e => Json.obj("key" -> e.key, "value" -> e.value, "updatedAt" -> e.updatedAt)))
        )

object StorageFunctions:
  private val KeyParameter =
    FunctionParameter("key", "string", required = true, "영문, 숫자, 마침표, 밑줄, 콜론, 슬래시, 하이픈으로 된 128자 이하의 키입니다.")

  private val ScopeParameter = FunctionParameter(
    "scope",
    "string",
    required = false,
    "USER는 호출한 사용자만 보는 값이고 APPLET은 앱 사용자 전체가 공유하는 값입니다. 기본값은 USER입니다.",
    StorageScope.values.toSeq.map(_.toString)
  )

  private def scope(arguments: FunctionArguments): StorageScope =
    arguments.optional[StorageScope]("scope").getOrElse(StorageScope.USER)
