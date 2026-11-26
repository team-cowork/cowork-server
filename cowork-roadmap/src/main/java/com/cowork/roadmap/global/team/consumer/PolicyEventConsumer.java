package com.cowork.roadmap.global.team.consumer;

import java.time.DateTimeException;
import java.util.function.Consumer;
import java.util.function.Function;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.cowork.roadmap.global.config.KafkaConsumerConfig;
import com.cowork.roadmap.global.team.event.RolePolicyState;
import com.cowork.roadmap.global.team.event.TeamAccessState;
import com.cowork.roadmap.global.team.service.ProjectionRecordProcessor;

import lombok.RequiredArgsConstructor;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class PolicyEventConsumer {
    private final ObjectMapper objectMapper;
    private final ProjectionRecordProcessor projectionRecordProcessor;

    @KafkaListener(topics = KafkaConsumerConfig.ACCOUNT_POLICY_TOPIC, groupId = KafkaConsumerConfig.ACCOUNT_POLICY_GROUP, containerFactory = "accountPolicyListenerContainerFactory")
    public void consumeAccount(ConsumerRecord<String, String> record) {
        consume(record,
                node -> RolePolicyState.parse(record.key(), node),
                state -> projectionRecordProcessor.applyPolicy(record, state));
    }

    @KafkaListener(topics = KafkaConsumerConfig.TEAM_ACCESS_TOPIC, groupId = KafkaConsumerConfig.TEAM_ACCESS_GROUP, containerFactory = "teamAccessListenerContainerFactory")
    public void consumeTeam(ConsumerRecord<String, String> record) {
        consume(record,
                node -> TeamAccessState.parse(record.key(), node),
                state -> projectionRecordProcessor.applyTeamAccess(record, state));
    }

    private <T> void consume(ConsumerRecord<String, String> record, Function<JsonNode, T> parse, Consumer<T> apply) {
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
        T state;
        try {
            state = parse.apply(objectMapper.readTree(record.value()));
        } catch (IllegalArgumentException | JacksonException | DateTimeException error) {
            projectionRecordProcessor.discard(record, "Invalid permission state");
            return;
        }
        apply.accept(state);
    }
}
