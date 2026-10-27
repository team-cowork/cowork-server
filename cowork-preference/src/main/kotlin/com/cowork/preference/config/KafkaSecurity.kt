package com.cowork.preference.config

fun kafkaSecurityProperties(): Map<String, String> {
    val protocol = (System.getenv("KAFKA_SECURITY_PROTOCOL") ?: "PLAINTEXT").uppercase()
    require(protocol in setOf("PLAINTEXT", "SSL", "SASL_SSL")) {
        "Use a supported Kafka security protocol"
    }
    return buildMap {
        put("security.protocol", protocol)
        if (protocol == "SASL_SSL") {
            val mechanism = (System.getenv("KAFKA_SASL_MECHANISM") ?: "SCRAM-SHA-512").uppercase()
            require(mechanism in setOf("SCRAM-SHA-256", "SCRAM-SHA-512")) { "Use a SCRAM SASL mechanism" }
            val username = quote(requireNotNull(System.getenv("KAFKA_SASL_USERNAME")) { "Provide KAFKA_SASL_USERNAME" })
            val password = quote(requireNotNull(System.getenv("KAFKA_SASL_PASSWORD")) { "Provide KAFKA_SASL_PASSWORD" })
            put("sasl.mechanism", mechanism)
            put(
                "sasl.jaas.config",
                "org.apache.kafka.common.security.scram.ScramLoginModule required " +
                    "username=\"$username\" password=\"$password\";",
            )
        }
        System.getenv("KAFKA_SSL_CA_LOCATION")?.takeIf(String::isNotBlank)?.let { caFile ->
            put("ssl.truststore.type", "PEM")
            put("ssl.truststore.location", caFile)
        }
    }
}

private fun quote(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")
