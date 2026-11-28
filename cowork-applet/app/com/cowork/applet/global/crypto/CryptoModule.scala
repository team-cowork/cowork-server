package com.cowork.applet.global.crypto

import com.google.inject.{AbstractModule, Provides, Singleton}
import play.api.Configuration

class CryptoModule extends AbstractModule:

  @Provides
  @Singleton
  def secretCipher(configuration: Configuration): SecretCipher =
    SecretCipher.fromBase64(configuration.get[String]("applet.secret-encryption-key"))
