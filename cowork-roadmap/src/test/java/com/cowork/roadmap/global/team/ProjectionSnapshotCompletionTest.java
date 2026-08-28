package com.cowork.roadmap.global.team;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

class ProjectionSnapshotCompletionTest {

    @Test
    void validatesTheSharedPerPartitionSnapshotMarkerContract() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("team.member.event",
                1,
                7L,
                ProjectionSnapshotCompletion.KEY_PREFIX + "1",
                """
                        {"eventType":"PROJECTION_SNAPSHOT_COMPLETED","topic":"team.member.event","partition":1,
                         "snapshotId":"00000000-0000-0000-0000-000000000001",
                         "occurredAt":"2026-08-26T00:00:00Z","source":"cowork-team"}
                        """);

        assertThat(ProjectionSnapshotCompletion.violation(JsonMapper.builder().build(), record)).isNull();
    }

    @Test
    void mismatchedReservedMarkerNeverInitializesTheProjection() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("team.member.event",
                1,
                7L,
                ProjectionSnapshotCompletion.KEY_PREFIX + "0",
                "{}");

        assertThat(ProjectionSnapshotCompletion.violation(JsonMapper.builder().build(), record)).contains("partition");
    }

    @Test
    void markerFromAnotherProducerNeverInitializesTheProjection() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("team.member.event",
                1,
                7L,
                ProjectionSnapshotCompletion.KEY_PREFIX + "1",
                """
                        {"eventType":"PROJECTION_SNAPSHOT_COMPLETED","topic":"team.member.event","partition":1,
                         "snapshotId":"00000000-0000-0000-0000-000000000001",
                         "occurredAt":"2026-08-26T00:00:00Z","source":"cowork-deprecated"}
                        """);

        assertThat(ProjectionSnapshotCompletion.violation(JsonMapper.builder().build(), record))
                .contains("cowork-team");
    }
}
