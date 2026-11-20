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
    private final ObjectMapper mapper;
    private final ProjectionRecordProcessor processor;

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
            String violation = ProjectionSnapshotCompletion.violation(mapper, record, "cowork-preference");
            if (violation == null) {
                processor.completeSnapshot(record, ProjectionSnapshotCompletion.snapshotId(mapper, record));
            } else {
                processor.discard(record, violation);
            }
            return;
        }
        RolePolicyState state;
        try {
            state = RolePolicyState.parse(record.key(), mapper.readTree(record.value()), teamScoped);
        } catch (IllegalArgumentException | JacksonException | DateTimeException error) {
            processor.discard(record, "Invalid role policy state");
            return;
        }
        processor.applyPolicy(record, state);
    }
}
