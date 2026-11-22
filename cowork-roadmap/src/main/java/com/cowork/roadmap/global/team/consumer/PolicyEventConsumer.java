package com.cowork.roadmap.global.team.consumer;

import java.time.DateTimeException;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.cowork.roadmap.global.config.KafkaConsumerConfig;
import com.cowork.roadmap.global.team.event.RolePolicyState;
import com.cowork.roadmap.global.team.service.ProjectionRecordProcessor;

import lombok.RequiredArgsConstructor;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class PolicyEventConsumer {
    private final ObjectMapper objectMapper;
    private final ProjectionRecordProcessor projectionRecordProcessor;

    @KafkaListener(topics = KafkaConsumerConfig.ACCOUNT_POLICY_TOPIC, groupId = KafkaConsumerConfig.ACCOUNT_POLICY_GROUP, containerFactory = "accountPolicyListenerContainerFactory")
    public void consumeAccount(ConsumerRecord<String, String> record) {
        consume(record, false);
    }

    @KafkaListener(topics = KafkaConsumerConfig.TEAM_POLICY_TOPIC, groupId = KafkaConsumerConfig.TEAM_POLICY_GROUP, containerFactory = "teamPolicyListenerContainerFactory")
    public void consumeTeam(ConsumerRecord<String, String> record) {
        consume(record, true);
    }

    private void consume(ConsumerRecord<String, String> record, boolean teamScoped) {
        if (ProjectionSnapshotCompletion.isReserved(record)) {
            String violation = ProjectionSnapshotCompletion.violation(objectMapper, record, "cowork-preference");
            if (violation == null) {
                projectionRecordProcessor.completeSnapshot(record,
                        ProjectionSnapshotCompletion.snapshotId(objectMapper, record));
            } else {
                projectionRecordProcessor.discard(record, violation);
            }
            return;
        }
        RolePolicyState state;
        try {
            state = RolePolicyState.parse(record.key(), objectMapper.readTree(record.value()), teamScoped);
        } catch (IllegalArgumentException | JacksonException | DateTimeException error) {
            projectionRecordProcessor.discard(record, "Invalid role policy state");
            return;
        }
        projectionRecordProcessor.applyPolicy(record, state);
    }
}
