package com.cowork.applet.domain.team.projection

import com.cowork.applet.global.kafka.{KafkaSettings, KafkaTopicInspector}
import com.cowork.applet.global.projection.{KafkaProjectionStream, ProjectionReadiness, ProjectionStore}
import com.google.inject.ImplementedBy
import java.util.concurrent.{Executors, TimeUnit}
import javax.inject.{Inject, Singleton}
import play.api.inject.ApplicationLifecycle
import scala.concurrent.Future

@ImplementedBy(classOf[TeamProjections])
trait TeamProjectionReadiness:
  def membersReady: Boolean
  def policiesReady: Boolean
  def isReady: Boolean = membersReady && policiesReady

// Starts the team projections that authorize team installations. Service readiness waits for both of them.
@Singleton
class TeamProjections @Inject() (
    kafkaSettings: KafkaSettings,
    kafkaTopicInspector: KafkaTopicInspector,
    projectionStore: ProjectionStore,
    applicationLifecycle: ApplicationLifecycle
) extends TeamProjectionReadiness:
  private val streams = Seq(TeamMemberProjection, TeamRolePolicyProjection).map { handler =>
    KafkaProjectionStream(
      kafkaSettings.clientProperties,
      projectionStore,
      ProjectionReadiness(projectionStore, kafkaTopicInspector, handler.group, handler.topic),
      handler
    )
  }
  private val members = streams.head.readiness
  private val policies = streams(1).readiness

  private val scheduler = Executors.newSingleThreadScheduledExecutor(runnable =>
    val thread = Thread(runnable, "projection-readiness")
    thread.setDaemon(true)
    thread
  )

  streams.foreach(_.start())
  scheduler.scheduleWithFixedDelay(() => streams.foreach(_.readiness.refresh()), 1, 1, TimeUnit.SECONDS)

  applicationLifecycle.addStopHook { () =>
    scheduler.shutdownNow()
    streams.foreach(_.stop())
    Future.unit
  }

  override def membersReady: Boolean = members.isReady

  override def policiesReady: Boolean = policies.isReady
