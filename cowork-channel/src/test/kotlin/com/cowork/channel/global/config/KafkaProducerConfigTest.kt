package com.cowork.channel.global.config

import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.kafka.autoconfigure.KafkaProperties
import org.springframework.context.annotation.Bean
import org.springframework.kafka.core.KafkaTemplate

class KafkaProducerConfigTest {

    @Test
    fun `result DLT producer serializes the original key and payload as raw strings`() {
        val producerFactory = KafkaProducerConfig(KafkaProperties())
            .channelRolePolicyResultDltProducerFactory()

        assertEquals(
            StringSerializer::class.java,
            producerFactory.configurationProperties[ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG],
        )
        assertEquals(
            StringSerializer::class.java,
            producerFactory.configurationProperties[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG],
        )
    }

    @Test
    fun `result consumer explicitly qualifies the dedicated DLT template bean`() {
        val constructor = KafkaConsumerConfig::class.java.declaredConstructors.single()
        val templateParameter = constructor.parameters.single { parameter ->
            parameter.type == KafkaTemplate::class.java
        }
        val qualifier = templateParameter.getAnnotation(Qualifier::class.java)
        val templateBean = KafkaProducerConfig::class.java
            .getDeclaredMethod("channelRolePolicyResultDltKafkaTemplate")
            .getAnnotation(Bean::class.java)

        assertEquals(CHANNEL_ROLE_POLICY_RESULT_DLT_TEMPLATE_BEAN, qualifier.value)
        assertTrue(CHANNEL_ROLE_POLICY_RESULT_DLT_TEMPLATE_BEAN in templateBean.name)
    }
}
