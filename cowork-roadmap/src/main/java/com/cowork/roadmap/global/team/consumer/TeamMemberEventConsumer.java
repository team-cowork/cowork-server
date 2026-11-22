package com.cowork.roadmap.global.team.consumer;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.cowork.roadmap.global.config.KafkaConsumerConfig;
import com.cowork.roadmap.global.team.event.TeamMemberEvent;
import com.cowork.roadmap.global.team.service.ProjectionRecordProcessor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class TeamMemberEventConsumer {

    private final ObjectMapper objectMapper;
    private final ProjectionRecordProcessor projectionRecordProcessor;

    @KafkaListener(topics = KafkaConsumerConfig.TEAM_MEMBER_TOPIC, groupId = KafkaConsumerConfig.TEAM_MEMBER_GROUP, containerFactory = "teamMemberListenerContainerFactory")
    public void consume(ConsumerRecord<String, String> record) {
        if (ProjectionSnapshotCompletion.isReserved(record)) {
            String violation = ProjectionSnapshotCompletion.violation(objectMapper, record, "cowork-team");
            if (violation == null) {
                projectionRecordProcessor.completeSnapshot(record,
                        ProjectionSnapshotCompletion.snapshotId(objectMapper, record));
            } else {
                log.error("Discarding invalid projection snapshot marker: {}", violation);
                discard(record, violation);
            }
            return;
        }

        final TeamMemberEvent event;
        try {
            event = objectMapper.readValue(record.value(), TeamMemberEvent.class);
        } catch (Exception exception) {
            log.error("Discarding malformed team.member.event payload", exception);
            discard(record, "team.member.event JSON is malformed: " + exception.getMessage());
            return;
        }
        if (event == null) {
            discard(record, "team.member.event top-level null is not allowed");
            return;
        }

        String expectedKey = event.teamId() + ":" + event.userId();
        if (!expectedKey.equals(record.key())) {
            log.error("Discarding team.member.event with mismatched key: expected={}, actual={}",
                    expectedKey,
                    record.key());
            discard(record, "team.member.event key does not match payload identity");
            return;
        }

        try {
            projectionRecordProcessor.apply(record, event);
        } catch (IllegalArgumentException exception) {
            log.error("Discarding invalid team.member.event payload", exception);
            discard(record, exception.getMessage());
        }
    }

    private void discard(ConsumerRecord<String, String> record, String reason) {
        projectionRecordProcessor.discard(record, reason == null ? "invalid team.member.event" : reason);
    }
}
