package config

import (
	"context"
	"fmt"
	"log/slog"
	"os"
	"strconv"
	"time"

	"github.com/cowork/cowork-voice/internal/config/springconfig"
)

type AppConfig struct {
	Port                        string
	MongoDBURI                  string
	MongoDBDB                   string
	RedisAddr                   string
	RedisPassword               string
	RedisDB                     int
	LiveKitURL                  string
	LiveKitWsURL                string
	LiveKitAPIKey               string
	LiveKitAPISecret            string
	LiveKitTokenTTLSecs         int64
	KafkaBrokers                string
	KafkaTopicVoiceEvent        string
	KafkaMessageTimeoutMs       int
	ChannelServiceURL           string
	EurekaEnabled               bool
	EurekaServerURL             string
	EurekaAppName               string
	EurekaInstanceHost          string
	EurekaInstancePort          int
	EurekaHeartbeatIntervalSecs int
}

func Load() (*AppConfig, error) {
	flatMap := fetchFromConfigServer()

	mongoURI, err := requireConfig(flatMap, "MONGODB_URI")
	if err != nil {
		return nil, err
	}
	mongoDB, err := requireConfig(flatMap, "MONGODB_DB")
	if err != nil {
		return nil, err
	}
	redisDB, err := strconv.Atoi(lookup(flatMap, "REDIS_DB", "0"))
	if err != nil {
		return nil, fmt.Errorf("invalid REDIS_DB: %w", err)
	}

	liveKitURL, err := requireConfig(flatMap, "LIVEKIT_URL")
	if err != nil {
		return nil, err
	}
	liveKitWsURL, err := requireConfig(flatMap, "LIVEKIT_WS_URL")
	if err != nil {
		return nil, err
	}
	liveKitAPIKey, err := requireConfig(flatMap, "LIVEKIT_API_KEY")
	if err != nil {
		return nil, err
	}
	liveKitAPISecret, err := requireConfig(flatMap, "LIVEKIT_API_SECRET")
	if err != nil {
		return nil, err
	}
	kafkaBrokers, err := requireConfig(flatMap, "KAFKA_BROKERS")
	if err != nil {
		return nil, err
	}
	kafkaTopic, err := requireConfig(flatMap, "KAFKA_TOPIC_VOICE_EVENT")
	if err != nil {
		return nil, err
	}
	channelServiceURL, err := requireConfig(flatMap, "CHANNEL_SERVICE_URL")
	if err != nil {
		return nil, err
	}

	ttlSecs, err := strconv.ParseInt(lookup(flatMap, "LIVEKIT_TOKEN_TTL_SECS", "3600"), 10, 64)
	if err != nil {
		return nil, fmt.Errorf("invalid LIVEKIT_TOKEN_TTL_SECS: %w", err)
	}

	timeoutMs, err := strconv.Atoi(lookup(flatMap, "KAFKA_MESSAGE_TIMEOUT_MS", "5000"))
	if err != nil {
		return nil, fmt.Errorf("invalid KAFKA_MESSAGE_TIMEOUT_MS: %w", err)
	}

	eurekaPort, err := strconv.Atoi(lookup(flatMap, "EUREKA_INSTANCE_PORT", lookup(flatMap, "PORT", "8084")))
	if err != nil {
		return nil, fmt.Errorf("invalid EUREKA_INSTANCE_PORT: %w", err)
	}

	heartbeatIntervalSecs, err := strconv.Atoi(lookup(flatMap, "EUREKA_HEARTBEAT_INTERVAL_SECS", "10"))
	if err != nil {
		return nil, fmt.Errorf("invalid EUREKA_HEARTBEAT_INTERVAL_SECS: %w", err)
	}

	cfg := &AppConfig{
		Port:                        lookup(flatMap, "PORT", "8084"),
		MongoDBURI:                  mongoURI,
		MongoDBDB:                   mongoDB,
		RedisAddr:                   lookup(flatMap, "REDIS_ADDR", "localhost:6379"),
		RedisPassword:               lookup(flatMap, "REDIS_PASSWORD", ""),
		RedisDB:                     redisDB,
		LiveKitURL:                  liveKitURL,
		LiveKitWsURL:                liveKitWsURL,
		LiveKitAPIKey:               liveKitAPIKey,
		LiveKitAPISecret:            liveKitAPISecret,
		LiveKitTokenTTLSecs:         ttlSecs,
		KafkaBrokers:                kafkaBrokers,
		KafkaTopicVoiceEvent:        kafkaTopic,
		KafkaMessageTimeoutMs:       timeoutMs,
		ChannelServiceURL:           channelServiceURL,
		EurekaEnabled:               lookup(flatMap, "EUREKA_ENABLED", "true") != "false",
		EurekaServerURL:             lookup(flatMap, "EUREKA_SERVER_URL", "http://localhost:8761/eureka"),
		EurekaAppName:               lookup(flatMap, "EUREKA_APP_NAME", "cowork-voice"),
		EurekaInstanceHost:          lookup(flatMap, "EUREKA_INSTANCE_HOST", "localhost"),
		EurekaInstancePort:          eurekaPort,
		EurekaHeartbeatIntervalSecs: heartbeatIntervalSecs,
	}

	overrideFromEnv(cfg)
	return cfg, nil
}

func fetchFromConfigServer() map[string]string {
	configURL := os.Getenv("APP_CONFIG_URL")
	if configURL == "" {
		return map[string]string{}
	}

	profile := getEnv("APP_PROFILE", "local")
	client := springconfig.NewClient(configURL, "cowork-voice", profile)

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	flatMap, err := client.Fetch(ctx)
	if err != nil {
		slog.Warn("config server unavailable, falling back to env vars only", "err", err)
		return map[string]string{}
	}

	slog.Info("config loaded from config server", "profile", profile, "keys", len(flatMap))
	return flatMap
}

func getEnv(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

func lookup(flatMap map[string]string, key, fallback string) string {
	if v, ok := flatMap[key]; ok && v != "" {
		return v
	}
	return fallback
}

func requireConfig(flatMap map[string]string, key string) (string, error) {
	if v := lookup(flatMap, key, ""); v != "" {
		return v, nil
	}
	if v := os.Getenv(key); v != "" {
		return v, nil
	}
	return "", fmt.Errorf("required configuration %q is not set", key)
}

func overrideFromEnv(cfg *AppConfig) {
	if v := os.Getenv("PORT"); v != "" {
		cfg.Port = v
	}
	if v := os.Getenv("MONGODB_URI"); v != "" {
		cfg.MongoDBURI = v
	}
	if v := os.Getenv("MONGODB_DB"); v != "" {
		cfg.MongoDBDB = v
	}
	if v := os.Getenv("REDIS_ADDR"); v != "" {
		cfg.RedisAddr = v
	}
	if v := os.Getenv("REDIS_PASSWORD"); v != "" {
		cfg.RedisPassword = v
	}
	if v := os.Getenv("REDIS_DB"); v != "" {
		if parsed, err := strconv.Atoi(v); err == nil {
			cfg.RedisDB = parsed
		}
	}
	if v := os.Getenv("LIVEKIT_URL"); v != "" {
		cfg.LiveKitURL = v
	}
	if v := os.Getenv("LIVEKIT_WS_URL"); v != "" {
		cfg.LiveKitWsURL = v
	}
	if v := os.Getenv("LIVEKIT_API_KEY"); v != "" {
		cfg.LiveKitAPIKey = v
	}
	if v := os.Getenv("LIVEKIT_API_SECRET"); v != "" {
		cfg.LiveKitAPISecret = v
	}
	if v := os.Getenv("LIVEKIT_TOKEN_TTL_SECS"); v != "" {
		if parsed, err := strconv.ParseInt(v, 10, 64); err == nil {
			cfg.LiveKitTokenTTLSecs = parsed
		}
	}
	if v := os.Getenv("KAFKA_BROKERS"); v != "" {
		cfg.KafkaBrokers = v
	}
	if v := os.Getenv("KAFKA_TOPIC_VOICE_EVENT"); v != "" {
		cfg.KafkaTopicVoiceEvent = v
	}
	if v := os.Getenv("KAFKA_MESSAGE_TIMEOUT_MS"); v != "" {
		if parsed, err := strconv.Atoi(v); err == nil {
			cfg.KafkaMessageTimeoutMs = parsed
		}
	}
	if v := os.Getenv("CHANNEL_SERVICE_URL"); v != "" {
		cfg.ChannelServiceURL = v
	}
	if v := os.Getenv("EUREKA_ENABLED"); v != "" {
		cfg.EurekaEnabled = v != "false"
	}
	if v := os.Getenv("EUREKA_SERVER_URL"); v != "" {
		cfg.EurekaServerURL = v
	}
	if v := os.Getenv("EUREKA_APP_NAME"); v != "" {
		cfg.EurekaAppName = v
	}
	if v := os.Getenv("EUREKA_INSTANCE_HOST"); v != "" {
		cfg.EurekaInstanceHost = v
	}
	if v := os.Getenv("EUREKA_INSTANCE_PORT"); v != "" {
		if parsed, err := strconv.Atoi(v); err == nil {
			cfg.EurekaInstancePort = parsed
		}
	} else if v := os.Getenv("PORT"); v != "" {
		// EUREKA_INSTANCE_PORT가 별도 지정되지 않으면 서버 리슨 포트(PORT)를 따라가
		// Eureka 등록 포트와 실제 포트가 어긋나지 않게 한다.
		if parsed, err := strconv.Atoi(v); err == nil {
			cfg.EurekaInstancePort = parsed
		}
	}
	if v := os.Getenv("EUREKA_HEARTBEAT_INTERVAL_SECS"); v != "" {
		if parsed, err := strconv.Atoi(v); err == nil {
			cfg.EurekaHeartbeatIntervalSecs = parsed
		}
	}
}
