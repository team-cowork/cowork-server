package config

import (
	"fmt"
	"net"
	"net/url"
	"os"
	"strconv"
)

type AppConfig struct {
	KafkaSecurity             KafkaSecurity
	Port                      string
	MongoDBURI                string
	MongoDBDB                 string
	LiveKitURL                string
	LiveKitWsURL              string
	LiveKitAPIKey             string
	LiveKitAPISecret          string
	LiveKitTokenTTLSecs       int64
	KafkaBrokers              string
	KafkaTopicVoiceEvent      string
	KafkaGroupIDChannelAccess string
	KafkaMessageTimeoutMs     int
}

func Load() (*AppConfig, error) {
	security, err := loadKafkaSecurity()
	if err != nil {
		return nil, err
	}
	for _, key := range []string{
		"DB_HOST", "DB_PORT", "DB_NAME", "DB_USERNAME", "DB_PASSWORD",
		"LIVEKIT_URL", "LIVEKIT_WS_URL", "LIVEKIT_API_KEY", "LIVEKIT_API_SECRET",
		"KAFKA_BOOTSTRAP_SERVERS", "KAFKA_TOPIC_VOICE_EVENT",
	} {
		if os.Getenv(key) == "" {
			return nil, fmt.Errorf("required configuration %q is not set", key)
		}
	}
	mongoURI, mongoDB := mongoConfig()
	cfg := &AppConfig{
		KafkaSecurity: security,
		Port:          getEnv("PORT", "8089"), MongoDBURI: mongoURI, MongoDBDB: mongoDB,
		LiveKitURL: os.Getenv("LIVEKIT_URL"), LiveKitWsURL: os.Getenv("LIVEKIT_WS_URL"),
		LiveKitAPIKey: os.Getenv("LIVEKIT_API_KEY"), LiveKitAPISecret: os.Getenv("LIVEKIT_API_SECRET"),
		KafkaBrokers:              os.Getenv("KAFKA_BOOTSTRAP_SERVERS"),
		KafkaTopicVoiceEvent:      os.Getenv("KAFKA_TOPIC_VOICE_EVENT"),
		KafkaGroupIDChannelAccess: getEnv("KAFKA_GROUP_ID_CHANNEL_ACCESS", "cowork-voice.channel-permissions.v2"),
	}
	cfg.LiveKitTokenTTLSecs, err = strconv.ParseInt(getEnv("LIVEKIT_TOKEN_TTL_SECS", "3600"), 10, 64)
	if err != nil || cfg.LiveKitTokenTTLSecs <= 0 {
		return nil, fmt.Errorf("LIVEKIT_TOKEN_TTL_SECS must be a positive integer")
	}
	cfg.KafkaMessageTimeoutMs, err = strconv.Atoi(getEnv("KAFKA_MESSAGE_TIMEOUT_MS", "5000"))
	if err != nil || cfg.KafkaMessageTimeoutMs <= 0 {
		return nil, fmt.Errorf("KAFKA_MESSAGE_TIMEOUT_MS must be a positive integer")
	}
	return cfg, nil
}

func getEnv(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

func mongoConfig() (string, string) {
	database := os.Getenv("DB_NAME")
	uri := url.URL{
		Scheme:   "mongodb",
		User:     url.UserPassword(os.Getenv("DB_USERNAME"), os.Getenv("DB_PASSWORD")),
		Host:     net.JoinHostPort(os.Getenv("DB_HOST"), os.Getenv("DB_PORT")),
		Path:     "/" + database,
		RawPath:  "/" + url.PathEscape(database),
		RawQuery: "authSource=admin",
	}
	return uri.String(), database
}
