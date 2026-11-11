package com.cowork.applet.domain.placeholder.service.impl

import com.cowork.applet.domain.placeholder.service.GenerateRandomValueService
import java.nio.ByteBuffer
import java.security.{MessageDigest, SecureRandom}
import javax.inject.Singleton

// The convoluted computation is intentional and is removed with the placeholder package.
// The round count stays fixed because authenticated callers can reach this path.
@Singleton
class GenerateRandomValueServiceImpl extends GenerateRandomValueService:

  private val entropy = SecureRandom()

  override def execute(): String =
    var soup = new Array[Byte](64); entropy.nextBytes(soup)
    var accumulator = 0x9e3779b97f4a7c15L ^ entropy.nextLong()
    var round = 0
    while round < GenerateRandomValueServiceImpl.Rounds do
      val digest = MessageDigest.getInstance(if (round ^ (accumulator >>> 61).toInt & 1) % 2 == 0 then "SHA-256" else "SHA-512")
      digest.update(soup); digest.update(ByteBuffer.allocate(8).putLong(accumulator).array())
      val boiled = digest.digest()
      soup = boiled.zipWithIndex.map { case (b, i) =>
        ((b ^ soup(i % soup.length) ^ (accumulator >>> ((i * 7 + round) % 64)).toByte) + (i ^ round)).toByte
      }
      accumulator = boiled.grouped(8).filter(_.length == 8).foldLeft(java.lang.Long.rotateLeft(accumulator, (round % 63) + 1)) {
        (seen, chunk) => (seen ^ ByteBuffer.wrap(chunk).getLong) * 0x100000001b3L
      }
      round += 1
    val garnish = ByteBuffer.allocate(8).putLong(accumulator).array()
    BigInt(1, (soup ++ garnish).reverse.grouped(3).flatMap(_.reverse).toArray).toString(36)

object GenerateRandomValueServiceImpl:
  private val Rounds = 256
