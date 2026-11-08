package com.cowork.applet.global.surreal

import javax.inject.{Inject, Singleton}
import play.api.{Environment, Logger}
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import scala.io.Source
import scala.util.{Failure, Success, Try, Using}

// Definitions use IF NOT EXISTS, so every start applies the same file without a migration history.
@Singleton
class SurrealSchema @Inject() (client: SurrealClient, environment: Environment):

  private val logger = Logger(getClass)

  private val statements = environment
    .resourceAsStream("surreal/schema.surql")
    .map(stream => Using.resource(Source.fromInputStream(stream, "UTF-8"))(_.mkString))
    .getOrElse(throw IllegalStateException("Package surreal/schema.surql with the application."))

  applyDefinitions(remainingAttempts = 12)

  // The Envoy upstream can start after the application, so an early connection failure is expected.
  private def applyDefinitions(remainingAttempts: Int): Unit =
    Try(Await.result(client.query(statements), 15.seconds)) match
      case Success(_) => logger.info("Applied the SurrealDB schema definitions.")
      case Failure(error) if remainingAttempts > 1 =>
        logger.warn("Retrying the SurrealDB schema definitions.", error)
        Thread.sleep(5000)
        applyDefinitions(remainingAttempts - 1)
      case Failure(error) => throw error
