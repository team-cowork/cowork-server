package com.cowork.applet.global.projection

import org.apache.kafka.clients.consumer.ConsumerRecord

trait ProjectionRecordHandler:
  def topic: String
  // The consumer group name must start with the cowork-applet Kafka principal.
  def group: String
  def snapshotSource: String
  // Returns the projection change, or the reason the record violates the contract.
  def mutation(record: ConsumerRecord[String, String]): Either[String, ProjectionMutation]
