package com.cowork.roadmap.global.team;

import java.time.Instant;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerRecord;

import tools.jackson.databind.ObjectMapper;

final class ProjectionSnapshotCompletion {

    static final String EVENT_TYPE = "PROJECTION_SNAPSHOT_COMPLETED";
    static final String KEY_PREFIX = "__cowork_projection_snapshot_complete__:";
    private static final String EXPECTED_SOURCE = "cowork-team";

    private ProjectionSnapshotCompletion() {
    }

    static boolean isReserved(ConsumerRecord<String, String> record) {
        return record.key() != null && record.key().startsWith(KEY_PREFIX);
    }

    static String violation(ObjectMapper objectMapper, ConsumerRecord<String, String> record) {
        if (!(KEY_PREFIX + record.partition()).equals(record.key())) {
            return "projection snapshot marker key does not match its partition";
        }
        final tools.jackson.databind.JsonNode payload;
        try {
            payload = objectMapper.readTree(record.value());
        } catch (Exception exception) {
            return "projection snapshot marker JSON is invalid: " + exception.getMessage();
        }
        if (payload == null || !EVENT_TYPE.equals(payload.path("eventType").asText())) {
            return "projection snapshot marker eventType is invalid";
        }
        if (!record.topic().equals(payload.path("topic").asText())) {
            return "projection snapshot marker topic does not match the record";
        }
        if (!payload.path("partition").canConvertToInt() || payload.path("partition").asInt() != record.partition()) {
            return "projection snapshot marker partition does not match the record";
        }
        try {
            UUID.fromString(payload.path("snapshotId").asText());
        } catch (Exception exception) {
            return "projection snapshot marker snapshotId is invalid";
        }
        try {
            Instant.parse(payload.path("occurredAt").asText());
        } catch (Exception exception) {
            return "projection snapshot marker occurredAt is invalid";
        }
        if (!EXPECTED_SOURCE.equals(payload.path("source").asText())) {
            return "projection snapshot marker source does not match the expected producer: " + EXPECTED_SOURCE;
        }
        return null;
    }
}
