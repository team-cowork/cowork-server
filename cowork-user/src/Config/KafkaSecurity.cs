using Confluent.Kafka;

namespace CoworkUser.Config;

public sealed class KafkaSecurity
{
    SecurityProtocol protocol;
    SaslMechanism mechanism;
    string? username;
    string? password;
    string? caLocation;

    public static KafkaSecurity Load()
    {
        string Env(string key, string fallback = "") =>
            Environment.GetEnvironmentVariable(key) is { Length: > 0 } value ? value : fallback;

        var security = new KafkaSecurity
        {
            protocol = Env("KAFKA_SECURITY_PROTOCOL", "PLAINTEXT").ToUpperInvariant() switch
            {
                "PLAINTEXT" => SecurityProtocol.Plaintext,
                "SSL" => SecurityProtocol.Ssl,
                "SASL_SSL" => SecurityProtocol.SaslSsl,
                _ => throw new InvalidOperationException("KAFKA_SECURITY_PROTOCOL must be PLAINTEXT, SSL or SASL_SSL"),
            },
        };

        var username = Env("KAFKA_SASL_USERNAME");
        var password = Env("KAFKA_SASL_PASSWORD");
        var caLocation = Env("KAFKA_SSL_CA_LOCATION");
        if (security.protocol == SecurityProtocol.Plaintext && caLocation != "")
            throw new InvalidOperationException("Kafka CA requires KAFKA_SECURITY_PROTOCOL=SSL or SASL_SSL");
        if (caLocation != "")
        {
            if (!File.Exists(caLocation)) throw new InvalidOperationException("KAFKA_SSL_CA_LOCATION is not a readable certificate file");
            security.caLocation = caLocation;
        }
        if (security.protocol != SecurityProtocol.SaslSsl)
        {
            if (username != "" || password != "")
                throw new InvalidOperationException("Kafka SASL credentials require KAFKA_SECURITY_PROTOCOL=SASL_SSL");
            return security;
        }
        if (username == "" || password == "")
            throw new InvalidOperationException("KAFKA_SASL_USERNAME and KAFKA_SASL_PASSWORD are required");
        security.username = username;
        security.password = password;
        security.mechanism = Env("KAFKA_SASL_MECHANISM", "SCRAM-SHA-512").ToUpperInvariant() switch
        {
            "SCRAM-SHA-256" => SaslMechanism.ScramSha256,
            "SCRAM-SHA-512" => SaslMechanism.ScramSha512,
            _ => throw new InvalidOperationException("KAFKA_SASL_MECHANISM must be SCRAM-SHA-256 or SCRAM-SHA-512"),
        };
        return security;
    }

    // Confluent config constructors share their backing dictionary, so each client needs a fresh one.
    public ClientConfig CreateClientConfig()
    {
        var config = new ClientConfig { SecurityProtocol = protocol };
        if (caLocation is not null) config.SslCaLocation = caLocation;
        if (protocol == SecurityProtocol.SaslSsl)
        {
            config.SaslMechanism = mechanism;
            config.SaslUsername = username;
            config.SaslPassword = password;
        }
        return config;
    }
}
