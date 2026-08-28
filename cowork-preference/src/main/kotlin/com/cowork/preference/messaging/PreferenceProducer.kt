package com.cowork.preference.messaging

import io.vertx.kafka.client.producer.KafkaProducer
import io.vertx.kafka.client.producer.KafkaProducerRecord
import io.vertx.kotlin.coroutines.coAwait

class PreferenceProducer(private val producer: KafkaProducer<String, String>) {
    internal suspend fun publishOutboxEvent(event: PreferenceEvent) = send(event)

    private suspend fun send(event: PreferenceEvent) {
        val record = event.partition?.let { partition ->
            KafkaProducerRecord.create(event.topic, event.key, event.payload.encode(), partition)
        } ?: KafkaProducerRecord.create(event.topic, event.key, event.payload.encode())
        producer.send(record).coAwait()
    }
}
