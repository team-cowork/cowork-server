package com.cowork.roadmap.global.team;

import java.time.Duration;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class TeamMemberEventConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(TeamMemberEventConsumer.class);
    private static final Duration APPLY_TIMEOUT = Duration.ofSeconds(10);

    private final ObjectMapper objectMapper;
    private final TeamMemberProjectionProcessor processor;

    @KafkaListener(topics = "${KAFKA_TOPIC_TEAM_MEMBER:team.member.event}", groupId = "${KAFKA_GROUP_ID_TEAM_MEMBER:cowork-roadmap.team-member}", containerFactory = "teamMemberListenerContainerFactory")
    public void consume(ConsumerRecord<String, String> record) {
        if (ProjectionSnapshotCompletion.isReserved(record)) {
            String violation = ProjectionSnapshotCompletion.violation(objectMapper, record);
            if (violation == null) {
                processor.completeSnapshot(record, ProjectionSnapshotCompletion.snapshotId(objectMapper, record))
                        .block(APPLY_TIMEOUT);
            } else {
                LOG.error("Discarding invalid projection snapshot marker: {}", violation);
                discard(record, violation);
            }
            return;
        }

        final TeamMemberEvent event;
        try {
            event = objectMapper.readValue(record.value(), TeamMemberEvent.class);
        } catch (Exception exception) {
            LOG.error("Discarding malformed team.member.event payload", exception);
            discard(record, "team.member.event JSON is malformed: " + exception.getMessage());
            return;
        }
        if (event == null) {
            discard(record, "team.member.event top-level null is not allowed");
            return;
        }

        String expectedKey = event.teamId() + ":" + event.userId();
        if (!expectedKey.equals(record.key())) {
            LOG.error("Discarding team.member.event with mismatched key: expected={}, actual={}",
                    expectedKey,
                    record.key());
            discard(record, "team.member.event key does not match payload identity");
            return;
        }

        try {
            processor.apply(record, event).block(APPLY_TIMEOUT);
        } catch (IllegalArgumentException exception) {
            LOG.error("Discarding invalid team.member.event payload", exception);
            discard(record, exception.getMessage());
        }
    }

    private void discard(ConsumerRecord<String, String> record, String reason) {
        processor.discard(record, reason == null ? "invalid team.member.event" : reason).block(APPLY_TIMEOUT);
    }
}
