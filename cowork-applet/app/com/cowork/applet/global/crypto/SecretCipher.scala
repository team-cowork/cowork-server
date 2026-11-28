package com.cowork.applet.global.crypto

import java.nio.charset.StandardCharsets.UTF_8
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.{GCMParameterSpec, SecretKeySpec}

// Stored format: base64(12-byte IV):base64(ciphertext + 16-byte GCM tag).
// The associated data binds a ciphertext to its owner, so a copied value fails to decrypt under another record.
final class SecretCipher(key: Array[Byte]):
  require(key.length == 32, "The applet secret encryption key must be 32 bytes.")

  private val secretKey = SecretKeySpec(key.clone(), "AES")
  private val random = SecureRandom()

  def encrypt(plaintext: String, associatedData: String): String =
    val iv = new Array[Byte](SecretCipher.IvLength)
    random.nextBytes(iv)
    val ciphertext = cipher(Cipher.ENCRYPT_MODE, iv, associatedData).doFinal(plaintext.getBytes(UTF_8))
    s"${SecretCipher.encoder.encodeToString(iv)}:${SecretCipher.encoder.encodeToString(ciphertext)}"

  def decrypt(encrypted: String, associatedData: String): String =
    encrypted.split(':') match
      case Array(iv, ciphertext) =>
        val plaintext = cipher(Cipher.DECRYPT_MODE, SecretCipher.decoder.decode(iv), associatedData)
          .doFinal(SecretCipher.decoder.decode(ciphertext))
        String(plaintext, UTF_8)
      case _ => throw IllegalArgumentException("The encrypted secret has an invalid format.")

  private def cipher(mode: Int, iv: Array[Byte], associatedData: String): Cipher =
    val instance = Cipher.getInstance("AES/GCM/NoPadding")
    instance.init(mode, secretKey, GCMParameterSpec(SecretCipher.TagBits, iv))
    instance.updateAAD(associatedData.getBytes(UTF_8))
    instance

object SecretCipher:
  private val IvLength = 12
  private val TagBits = 128
  private val encoder = Base64.getEncoder
  private val decoder = Base64.getDecoder

  def fromBase64(encodedKey: String): SecretCipher = SecretCipher(decoder.decode(encodedKey.trim))
