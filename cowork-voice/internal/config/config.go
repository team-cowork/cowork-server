package config

import (
	"fmt"
	"os"
	"strconv"
)

type AppConfig struct {
	Port                  string
	MongoDBURI            string
	MongoDBDB             string
	LiveKitURL            string
	LiveKitWsURL          string
	LiveKitAPIKey         string
	LiveKitAPISecret      string
	LiveKitTokenTTLSecs   int64
	KafkaBrokers          string
	KafkaTopicVoiceEvent  string
	KafkaMessageTimeoutMs int
	ChannelServiceURL     string
	EurekaServerURL       string
	EurekaAppName         string
	EurekaInstanceHost    string
	EurekaInstancePort    int
}

func Load() (*AppConfig, error) {
	mongoURI, err := requireEnv("MONGODB_URI")
	if err != nil {
		return nil, err
	}
	mongoDB, err := requireEnv("MONGODB_DB")
	if err != nil {
		return nil, err
	}
	liveKitURL, err := requireEnv("LIVEKIT_URL")
	if err != nil {
		return nil, err
	}
	liveKitWsURL, err := requireEnv("LIVEKIT_WS_URL")
	if err != nil {
		return nil, err
	}
	liveKitAPIKey, err := requireEnv("LIVEKIT_API_KEY")
	if err != nil {
		return nil, err
	}
	liveKitAPISecret, err := requireEnv("LIVEKIT_API_SECRET")
	if err != nil {
		return nil, err
	}
	kafkaBrokers, err := requireEnv("KAFKA_BROKERS")
	if err != nil {
		return nil, err
	}
	kafkaTopic, err := requireEnv("KAFKA_TOPIC_VOICE_EVENT")
	if err != nil {
		return nil, err
	}
	channelServiceURL, err := requireEnv("CHANNEL_SERVICE_URL")
	if err != nil {
		return nil, err
	}

	ttlSecs, err := strconv.ParseInt(getEnv("LIVEKIT_TOKEN_TTL_SECS", "3600"), 10, 64)
	if err != nil {
		return nil, fmt.Errorf("invalid LIVEKIT_TOKEN_TTL_SECS: %w", err)
	}

	timeoutMs, err := strconv.Atoi(getEnv("KAFKA_MESSAGE_TIMEOUT_MS", "5000"))
	if err != nil {
		return nil, fmt.Errorf("invalid KAFKA_MESSAGE_TIMEOUT_MS: %w", err)
	}

	eurekaPort, err := strconv.Atoi(getEnv("EUREKA_INSTANCE_PORT", "8084"))
	if err != nil {
		return nil, fmt.Errorf("invalid EUREKA_INSTANCE_PORT: %w", err)
	}

	return &AppConfig{
		Port:                  getEnv("PORT", "8084"),
		MongoDBURI:            mongoURI,
		MongoDBDB:             mongoDB,
		LiveKitURL:            liveKitURL,
		LiveKitWsURL:          liveKitWsURL,
		LiveKitAPIKey:         liveKitAPIKey,
		LiveKitAPISecret:      liveKitAPISecret,
		LiveKitTokenTTLSecs:   ttlSecs,
		KafkaBrokers:          kafkaBrokers,
		KafkaTopicVoiceEvent:  kafkaTopic,
		KafkaMessageTimeoutMs: timeoutMs,
		ChannelServiceURL:     channelServiceURL,
		EurekaServerURL:       getEnv("EUREKA_SERVER_URL", "http://localhost:8761/eureka"),
		EurekaAppName:         getEnv("EUREKA_APP_NAME", "cowork-voice"),
		EurekaInstanceHost:    getEnv("EUREKA_INSTANCE_HOST", "localhost"),
		EurekaInstancePort:    eurekaPort,
	}, nil
}

func getEnv(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

func requireEnv(key string) (string, error) {
	v := os.Getenv(key)
	if v == "" {
		return "", fmt.Errorf("required environment variable %q is not set", key)
	}
	return v, nil
}
