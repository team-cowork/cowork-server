package com.cowork.applet.global.kafka

import java.util.{Locale, Properties}
import javax.inject.{Inject, Singleton}
import play.api.Configuration

@Singleton
class KafkaSettings @Inject() (configuration: Configuration):

  // Shared by consumers and the admin client. Mirrors the security setup of the Spring services.
  def clientProperties: Properties =
    val properties = Properties()
    properties.put("bootstrap.servers", configuration.get[String]("kafka.bootstrap-servers"))
    // The broker has a fixed address; keep reconnecting without rebootstrap loops.
    properties.put("metadata.recovery.strategy", "none")
    properties.put("reconnect.backoff.ms", "1000")
    properties.put("reconnect.backoff.max.ms", "30000")
    properties.put("retry.backoff.ms", "1000")
    properties.put("retry.backoff.max.ms", "10000")
    val protocol = configuration.get[String]("kafka.security-protocol").toUpperCase(Locale.ROOT)
    require(Set("PLAINTEXT", "SSL", "SASL_SSL").contains(protocol), "Use a supported Kafka security protocol.")
    properties.put("security.protocol", protocol)
    if protocol != "PLAINTEXT" then
      properties.put("ssl.truststore.type", "PEM")
      properties.put("ssl.truststore.location", configuration.get[String]("kafka.ssl-ca-location"))
    if protocol == "SASL_SSL" then
      val mechanism = configuration.get[String]("kafka.sasl-mechanism").toUpperCase(Locale.ROOT)
      require(Set("SCRAM-SHA-256", "SCRAM-SHA-512").contains(mechanism), "Use a SCRAM SASL mechanism.")
      properties.put("sasl.mechanism", mechanism)
      properties.put("sasl.jaas.config", KafkaSettings.scramJaas(
        configuration.get[String]("kafka.sasl-username"),
        configuration.get[String]("kafka.sasl-password")
      ))
    properties

object KafkaSettings:
  def scramJaas(username: String, password: String): String =
    def quote(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"")
    s"""org.apache.kafka.common.security.scram.ScramLoginModule required username="${quote(username)}" password="${quote(password)}";"""
