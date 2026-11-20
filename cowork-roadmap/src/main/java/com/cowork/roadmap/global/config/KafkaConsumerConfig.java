package com.cowork.roadmap.global.config;

import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import com.cowork.roadmap.global.team.projection.readiness.ProjectionReadiness;
import com.cowork.roadmap.global.team.projection.topic.ProjectionTopicGenerationRegistry;
import com.cowork.roadmap.global.team.projection.topic.ProjectionTopicIdentityProvider;
import com.cowork.roadmap.global.team.repository.ProjectionCheckpointRepository;

@Configuration
@EnableKafka
public class KafkaConsumerConfig {

    public static final String ACCOUNT_POLICY_TOPIC = "preference.account-role.state.v1";
    public static final String TEAM_POLICY_TOPIC = "preference.team-role.state.v2";
    public static final String ACCOUNT_POLICY_GROUP = "cowork-roadmap.account-role.v1";
    public static final String TEAM_POLICY_GROUP = "cowork-roadmap.team-role.v2";

    @Value("${projection.readiness.assignment-retry-backoff-ms:1000}")
    private long retryBackoff;
    @Value("${projection.readiness.assignment-retry-timeout-ms:60000}")
    private long retryTimeout;

    @Bean
    ProjectionReadiness teamMemberProjectionReadiness(ProjectionCheckpointRepository checkpoints,
            ProjectionTopicIdentityProvider broker,
            @Value("${KAFKA_GROUP_ID_TEAM_MEMBER:cowork-roadmap.team-member}") String group,
            @Value("${KAFKA_TOPIC_TEAM_MEMBER:team.member.event}") String topic) {
        return readiness(checkpoints, broker, group, topic);
    }

    @Bean
    ProjectionReadiness accountPolicyReadiness(ProjectionCheckpointRepository checkpoints,
            ProjectionTopicIdentityProvider broker) {
        return readiness(checkpoints, broker, ACCOUNT_POLICY_GROUP, ACCOUNT_POLICY_TOPIC);
    }

    @Bean
    ProjectionReadiness teamPolicyReadiness(ProjectionCheckpointRepository checkpoints,
            ProjectionTopicIdentityProvider broker) {
        return readiness(checkpoints, broker, TEAM_POLICY_GROUP, TEAM_POLICY_TOPIC);
    }

    private ProjectionReadiness readiness(ProjectionCheckpointRepository checkpoints,
            ProjectionTopicIdentityProvider broker,
            String group,
            String topic) {
        return new ProjectionReadiness(checkpoints,
                broker,
                new ProjectionTopicGenerationRegistry(),
                group,
                topic,
                retryBackoff,
                retryTimeout);
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> teamMemberListenerContainerFactory(
            KafkaProperties properties,
            @Qualifier("teamMemberProjectionReadiness") ProjectionReadiness readiness) {
        return listenerFactory(properties, readiness);
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> accountPolicyListenerContainerFactory(
            KafkaProperties properties,
            @Qualifier("accountPolicyReadiness") ProjectionReadiness readiness) {
        return listenerFactory(properties, readiness);
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> teamPolicyListenerContainerFactory(
            KafkaProperties properties,
            @Qualifier("teamPolicyReadiness") ProjectionReadiness readiness) {
        return listenerFactory(properties, readiness);
    }

    private ConcurrentKafkaListenerContainerFactory<String, String> listenerFactory(KafkaProperties kafkaProperties,
            ProjectionReadiness projectionReadiness) {
        Map<String, Object> properties = new HashMap<>(kafkaProperties.buildConsumerProperties());
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(properties));
        // Projection storage failures must not advance the partition. Invalid contract
        // payloads
        // are handled and discarded by the listener before reaching this retry policy.
        factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(1_000L, Long.MAX_VALUE)));
        factory.getContainerProperties().setConsumerRebalanceListener(projectionReadiness);
        return factory;
    }
}
