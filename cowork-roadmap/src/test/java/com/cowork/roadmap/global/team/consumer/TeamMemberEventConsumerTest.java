package com.cowork.roadmap.global.team.consumer;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.cowork.roadmap.global.team.event.TeamMemberEvent;
import com.cowork.roadmap.global.team.service.ProjectionRecordProcessor;

import tools.jackson.databind.json.JsonMapper;

class TeamMemberEventConsumerTest {

    private static final String TOPIC = "team.member.event";
    private static final String PAYLOAD = """
            {"eventType":"UPSERT","teamId":1,"userId":2,"role":"MEMBER","teamName":"team",\
            "occurredAt":"2026-01-01T00:00:00Z"}""";
    private static final TeamMemberEvent EVENT = new TeamMemberEvent("UPSERT",
            1L,
            2L,
            "MEMBER",
            "team",
            Instant.parse("2026-01-01T00:00:00Z"));
    private static final String MARKER_KEY = ProjectionSnapshotCompletion.KEY_PREFIX + 0;
    private static final String SNAPSHOT_ID = "8f14e45f-ceea-467a-9575-6b5d1c1a2b3c";

    private final ProjectionRecordProcessor processor = mock(ProjectionRecordProcessor.class);
    private final TeamMemberEventConsumer consumer = new TeamMemberEventConsumer(JsonMapper.builder().build(),
            processor);

    @Test
    void matchingKey_appliesEvent() {
        ConsumerRecord<String, String> record = record("1:2", PAYLOAD);

        consumer.consume(record);

        verify(processor).apply(record, EVENT);
        verify(processor, never()).discard(any(), any());
    }

    @Test
    void mismatchedKey_discardsWithoutApplying() {
        ConsumerRecord<String, String> record = record("1:3", PAYLOAD);

        consumer.consume(record);

        verify(processor).discard(record, "team.member.event key does not match payload identity");
        verify(processor, never()).apply(any(), any());
    }

    @Test
    void missingKey_discardsWithoutApplying() {
        ConsumerRecord<String, String> record = record(null, PAYLOAD);

        consumer.consume(record);

        verify(processor).discard(record, "team.member.event key does not match payload identity");
        verify(processor, never()).apply(any(), any());
    }

    @Test
    void malformedJson_discardsWithoutApplying() {
        ConsumerRecord<String, String> record = record("1:2", "{\"eventType\":");
        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);

        consumer.consume(record);

        verify(processor).discard(eq(record), reason.capture());
        assertTrue(reason.getValue().startsWith("team.member.event JSON is malformed"));
        verify(processor, never()).apply(any(), any());
    }

    @Test
    void topLevelNull_discardsWithoutApplying() {
        ConsumerRecord<String, String> record = record("1:2", "null");

        consumer.consume(record);

        verify(processor).discard(record, "team.member.event top-level null is not allowed");
        verify(processor, never()).apply(any(), any());
    }

    @Test
    void rejectedPayload_isDiscardedWithRejectionReason() {
        ConsumerRecord<String, String> record = record("1:2", PAYLOAD);
        doThrow(new IllegalArgumentException("Invalid team.member.event payload")).when(processor).apply(record, EVENT);

        consumer.consume(record);

        verify(processor).discard(record, "Invalid team.member.event payload");
    }

    @Test
    void validSnapshotMarker_completesSnapshot() {
        ConsumerRecord<String, String> record = record(MARKER_KEY, marker("cowork-team"));

        consumer.consume(record);

        verify(processor).completeSnapshot(record, SNAPSHOT_ID);
        verify(processor, never()).discard(any(), any());
        verify(processor, never()).apply(any(), any());
    }

    @Test
    void invalidSnapshotMarker_discardsWithoutCompletingSnapshot() {
        ConsumerRecord<String, String> record = record(MARKER_KEY, marker("cowork-other"));

        consumer.consume(record);

        verify(processor).discard(record,
                "projection snapshot marker source does not match the expected producer: cowork-team");
        verify(processor, never()).completeSnapshot(any(), any());
        verify(processor, never()).apply(any(), any());
    }

    private static String marker(String source) {
        return """
                {"eventType":"PROJECTION_SNAPSHOT_COMPLETED","topic":"%s","partition":0,"snapshotId":"%s",\
                "occurredAt":"2026-01-01T00:00:00Z","source":"%s"}""".formatted(TOPIC, SNAPSHOT_ID, source);
    }

    private static ConsumerRecord<String, String> record(String key, String value) {
        return new ConsumerRecord<>(TOPIC, 0, 7L, key, value);
    }
}
