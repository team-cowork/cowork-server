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
	mongoURI, mongoDB, err := mongoConfig()
	if err != nil {
		return nil, err
	}
	cfg := &AppConfig{
		KafkaSecurity: security,
		Port:          getEnv("PORT", "8089"), MongoDBURI: mongoURI, MongoDBDB: mongoDB,
		LiveKitURL: os.Getenv("LIVEKIT_URL"), LiveKitWsURL: os.Getenv("LIVEKIT_WS_URL"),
		LiveKitAPIKey: os.Getenv("LIVEKIT_API_KEY"), LiveKitAPISecret: os.Getenv("LIVEKIT_API_SECRET"),
		KafkaBrokers:              os.Getenv("KAFKA_BOOTSTRAP_SERVERS"),
		KafkaTopicVoiceEvent:      os.Getenv("KAFKA_TOPIC_VOICE_EVENT"),
		KafkaGroupIDChannelAccess: getEnv("KAFKA_GROUP_ID_CHANNEL_ACCESS", "cowork-voice.channel-permissions.v1"),
	}
	for _, key := range []string{
		"LIVEKIT_URL", "LIVEKIT_WS_URL", "LIVEKIT_API_KEY", "LIVEKIT_API_SECRET",
		"KAFKA_BOOTSTRAP_SERVERS", "KAFKA_TOPIC_VOICE_EVENT",
	} {
		if _, err := requireConfig(key); err != nil {
			return nil, err
		}
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

func requireConfig(key string) (string, error) {
	if v := os.Getenv(key); v != "" {
		return v, nil
	}
	return "", fmt.Errorf("required configuration %q is not set", key)
}

// Individual database inputs replace the entire stored URI, including its credentials.
func mongoConfig() (string, string, error) {
	keys := []string{"DB_HOST", "DB_PORT", "DB_NAME", "DB_USERNAME", "DB_PASSWORD"}
	configured := false
	for _, key := range keys {
		if _, present := os.LookupEnv(key); present {
			configured = true
			break
		}
	}
	if !configured {
		uri, err := requireConfig("MONGODB_URI")
		if err != nil {
			return "", "", err
		}
		database, err := requireConfig("MONGODB_DB")
		return uri, database, err
	}
	for _, key := range keys {
		if os.Getenv(key) == "" {
			return "", "", fmt.Errorf("required configuration %q is not set", key)
		}
	}
	database := os.Getenv("DB_NAME")
	uri := url.URL{
		Scheme:   "mongodb",
		User:     url.UserPassword(os.Getenv("DB_USERNAME"), os.Getenv("DB_PASSWORD")),
		Host:     net.JoinHostPort(os.Getenv("DB_HOST"), os.Getenv("DB_PORT")),
		Path:     "/" + database,
		RawPath:  "/" + url.PathEscape(database),
		RawQuery: "authSource=admin",
	}
	return uri.String(), database, nil
}
