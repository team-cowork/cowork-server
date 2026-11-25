package com.cowork.applet.global.projection

import com.cowork.applet.global.surreal.SurrealClient
import java.time.Instant
import javax.inject.{Inject, Singleton}
import play.api.libs.json.{JsObject, Json}
import scala.concurrent.{ExecutionContext, Future}

// Statements and variables that change projection data. They run in the same transaction as the checkpoint.
final case class ProjectionMutation(statements: String, variables: JsObject)

final case class QuarantinedRecord(partition: Int, offset: Long, key: Option[String], payload: Option[String], reason: String)

@Singleton
class ProjectionStore @Inject() (surrealClient: SurrealClient)(using ExecutionContext):

  def checkpoints(group: String, topic: String): Future[Map[Int, ProjectionCheckpoint]] =
    surrealClient
      .query(
        "SELECT * OMIT id FROM applet_projection_offset WHERE consumerGroup = $group AND topic = $topic;",
        Json.obj("group" -> group, "topic" -> topic)
      )
      .map(results => results.head.as[Seq[JsObject]].map(row => (row \ "partition").as[Int] -> row.as[ProjectionCheckpoint]).toMap)

  def barriers(group: String, topic: String): Future[Map[Int, ProjectionBarrier]] =
    surrealClient
      .query(
        "SELECT * OMIT id FROM applet_projection_barrier WHERE consumerGroup = $group AND topic = $topic;",
        Json.obj("group" -> group, "topic" -> topic)
      )
      .map(results => results.head.as[Seq[JsObject]].map(row => (row \ "partition").as[Int] -> row.as[ProjectionBarrier]).toMap)

  // Records the barrier and checkpoint of every partition, and returns the seek offset of each assigned partition.
  def initializeAssignment(
      group: String,
      topic: String,
      topicId: String,
      ranges: Seq[PartitionRange],
      assigned: Set[Int]
  ): Future[Map[Int, Long]] =
    for
      existingCheckpoints <- checkpoints(group, topic)
      existingBarriers <- barriers(group, topic)
      plans = ranges.map { range =>
        val decision = ProjectionPolicy.seekDecision(existingCheckpoints.get(range.partition), range, topicId)
        (
          range.partition,
          decision.seekOffset,
          ProjectionPolicy.ensure(existingCheckpoints.get(range.partition), decision),
          ProjectionPolicy.barrier(existingBarriers.get(range.partition), topicId, range.endOffset)
        )
      }
      _ <- surrealClient.query(
        ProjectionStore.transaction(plans.indices.map(i => ProjectionStore.upsertPartition(i)).mkString("\n")),
        plans.zipWithIndex.foldLeft(Json.obj()) { case (variables, ((partition, _, checkpoint, barrier), i)) =>
          val keys = Json.obj("consumerGroup" -> group, "topic" -> topic, "partition" -> partition)
          variables ++ Json.obj(
            s"checkpoint$i" -> (keys ++ ProjectionStore.document(checkpoint)),
            s"barrier$i" -> (keys ++ Json.toJsObject(barrier) ++ Json.obj("capturedAt" -> Instant.now()))
          )
        }
      )
    yield plans.collect { case (partition, seek, _, _) if assigned.contains(partition) => partition -> seek }.toMap

  // Writes the next checkpoint only if the stored one is still the state this consumer read, and applies the
  // projection mutation and quarantine entry in the same transaction.
  def commit(
      group: String,
      topic: String,
      partition: Int,
      previous: ProjectionCheckpoint,
      next: ProjectionCheckpoint,
      mutation: Option[ProjectionMutation],
      quarantined: Option[QuarantinedRecord]
  ): Future[Unit] =
    val keys = Json.obj("consumerGroup" -> group, "topic" -> topic, "partition" -> partition)
    val checkpointStatement =
      """LET $cpUpdated = (UPDATE applet_projection_offset CONTENT $cpNext
        |  WHERE consumerGroup = $cpGroup AND topic = $cpTopic AND partition = $cpPartition
        |    AND topicId = $cpTopicId AND nextOffset = $cpExpectedNext);
        |IF array::len($cpUpdated) != 1 { THROW "Kafka projection checkpoint generation mismatch"; };""".stripMargin
    val quarantineStatement = quarantined.fold("") { _ =>
      """DELETE applet_projection_quarantine
        |  WHERE consumerGroup = $cpGroup AND topic = $cpTopic AND partition = $cpPartition AND recordOffset = $cpQuarantine.recordOffset;
        |CREATE applet_projection_quarantine CONTENT $cpQuarantine RETURN NONE;""".stripMargin
    }
    val variables = mutation.fold(Json.obj())(_.variables) ++ Json.obj(
      "cpGroup" -> group,
      "cpTopic" -> topic,
      "cpPartition" -> partition,
      "cpTopicId" -> previous.topicId,
      "cpExpectedNext" -> previous.nextOffset,
      "cpNext" -> (keys ++ ProjectionStore.document(next))
    ) ++ quarantined.fold(Json.obj())(record =>
      Json.obj(
        "cpQuarantine" -> (keys ++ Json.obj(
          "recordOffset" -> record.offset,
          "recordKey" -> record.key.map(_.take(512)),
          "payload" -> record.payload,
          "reason" -> record.reason.take(1000),
          "updatedAt" -> Instant.now()
        ))
      )
    )
    val statements = Seq(mutation.fold("")(_.statements), quarantineStatement, checkpointStatement).filter(_.nonEmpty)
    surrealClient.query(ProjectionStore.transaction(statements.mkString("\n")), variables).map(_ => ())

object ProjectionStore:
  private def transaction(statements: String): String = s"BEGIN TRANSACTION;\n$statements\nCOMMIT TRANSACTION;"

  private def document(checkpoint: ProjectionCheckpoint): JsObject =
    Json.toJsObject(checkpoint) ++ Json.obj("updatedAt" -> Instant.now())

  private def upsertPartition(i: Int): String =
    s"""LET $$checkpointRows$i = (UPDATE applet_projection_offset CONTENT $$checkpoint$i
       |  WHERE consumerGroup = $$checkpoint$i.consumerGroup AND topic = $$checkpoint$i.topic AND partition = $$checkpoint$i.partition);
       |IF array::len($$checkpointRows$i) == 0 { CREATE applet_projection_offset CONTENT $$checkpoint$i RETURN NONE; };
       |LET $$barrierRows$i = (UPDATE applet_projection_barrier CONTENT $$barrier$i
       |  WHERE consumerGroup = $$barrier$i.consumerGroup AND topic = $$barrier$i.topic AND partition = $$barrier$i.partition);
       |IF array::len($$barrierRows$i) == 0 { CREATE applet_projection_barrier CONTENT $$barrier$i RETURN NONE; };""".stripMargin
