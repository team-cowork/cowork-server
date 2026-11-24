package com.cowork.applet.domain.environment.service.impl

import com.cowork.applet.domain.applet.service.AppletReader
import com.cowork.applet.domain.environment.model.AppletSecret
import com.cowork.applet.domain.environment.repository.AppletSecretRepository
import com.cowork.applet.domain.environment.service.{EnvironmentEntries, PutAppletSecretService}
import com.cowork.applet.global.crypto.SecretCipher
import java.time.Instant
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class PutAppletSecretServiceImpl @Inject() (
    appletReader: AppletReader,
    appletSecretRepository: AppletSecretRepository,
    secretCipher: SecretCipher
)(using ExecutionContext)
    extends PutAppletSecretService:

  override def execute(userId: String, appletId: String, name: String, value: String): Future[AppletSecret] =
    val validName = EnvironmentEntries.name(name)
    val validValue = EnvironmentEntries.value(value)
    for
      applet <- appletReader.owned(appletId, userId)
      existing <- appletSecretRepository.findAll(applet.appletId)
      _ = EnvironmentEntries.requireCapacity(existing.exists(_.name == validName), existing.size)
      ciphertext = secretCipher.encrypt(validValue, AppletSecret.associatedData(applet.appletId, validName))
      secret = AppletSecret(applet.appletId, validName, ciphertext, Instant.now())
      _ <- appletSecretRepository.upsert(secret)
    yield secret
