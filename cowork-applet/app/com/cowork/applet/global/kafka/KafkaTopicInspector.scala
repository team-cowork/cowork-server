package com.cowork.applet.global.kafka

import com.cowork.applet.global.projection.{BrokerRange, TopicState}
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.List as JavaList
import javax.inject.{Inject, Singleton}
import org.apache.kafka.clients.admin.{Admin, OffsetSpec, TopicDescription}
import org.apache.kafka.common.{TopicPartition, Uuid}
import play.api.inject.ApplicationLifecycle
import scala.concurrent.Future
import scala.jdk.CollectionConverters.*

// Reads the broker topic UUID and offsets. The UUID shows when a topic was deleted and created again.
@Singleton
class KafkaTopicInspector @Inject() (kafkaSettings: KafkaSettings, applicationLifecycle: ApplicationLifecycle):

  private val TimeoutSeconds = 10L
  private val admin = Admin.create(kafkaSettings.clientProperties)

  applicationLifecycle.addStopHook(() => Future.successful(admin.close(Duration.ofSeconds(5))))

  def topicId(topic: String): String = describe(topic).topicId().toString

  def topicState(topic: String): TopicState =
    val description = describe(topic)
    val partitions = description.partitions().asScala.map(info => TopicPartition(topic, info.partition())).toSeq
    def offsets(spec: OffsetSpec) =
      admin.listOffsets(partitions.map(_ -> spec).toMap.asJava).all().get(TimeoutSeconds, TimeUnit.SECONDS).asScala
    val beginnings = offsets(OffsetSpec.earliest())
    val ends = offsets(OffsetSpec.latest())
    TopicState(
      description.topicId().toString,
      partitions.map(p => p.partition() -> BrokerRange(beginnings(p).offset(), ends(p).offset())).toMap
    )

  private def describe(topic: String): TopicDescription =
    val description = admin.describeTopics(JavaList.of(topic)).allTopicNames().get(TimeoutSeconds, TimeUnit.SECONDS).get(topic)
    if description == null || description.topicId() == Uuid.ZERO_UUID then
      throw IllegalStateException(s"Kafka topic ID is unavailable for $topic.")
    description
