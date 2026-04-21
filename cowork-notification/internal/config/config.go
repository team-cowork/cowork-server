package config

import (
	"fmt"
	"os"
	"strconv"
)

type AppConfig struct {
	Port                 string
	DBDSN                string
	KafkaBrokers         string
	KafkaTopicNotify     string
	KafkaGroupID         string
	FCMCredentialsFile   string
	PreferenceServiceURL string
	EurekaServerURL      string
	EurekaAppName        string
	EurekaInstanceHost   string
	EurekaInstancePort   int
}

func Load() (*AppConfig, error) {
	dbDSN, err := requireEnv("DB_DSN")
	if err != nil {
		return nil, err
	}
	kafkaBrokers, err := requireEnv("KAFKA_BROKERS")
	if err != nil {
		return nil, err
	}
	kafkaTopic, err := requireEnv("KAFKA_TOPIC_NOTIFICATION")
	if err != nil {
		return nil, err
	}
	kafkaGroup, err := requireEnv("KAFKA_GROUP_ID")
	if err != nil {
		return nil, err
	}
	fcmFile, err := requireEnv("FCM_CREDENTIALS_FILE")
	if err != nil {
		return nil, err
	}
	prefURL, err := requireEnv("PREFERENCE_SERVICE_URL")
	if err != nil {
		return nil, err
	}

	eurekaPort, err := strconv.Atoi(getEnv("EUREKA_INSTANCE_PORT", "8086"))
	if err != nil {
		return nil, fmt.Errorf("invalid EUREKA_INSTANCE_PORT: %w", err)
	}

	return &AppConfig{
		Port:                 getEnv("PORT", "8086"),
		DBDSN:                dbDSN,
		KafkaBrokers:         kafkaBrokers,
		KafkaTopicNotify:     kafkaTopic,
		KafkaGroupID:         kafkaGroup,
		FCMCredentialsFile:   fcmFile,
		PreferenceServiceURL: prefURL,
		EurekaServerURL:      getEnv("EUREKA_SERVER_URL", "http://localhost:8761/eureka"),
		EurekaAppName:        getEnv("EUREKA_APP_NAME", "cowork-notification"),
		EurekaInstanceHost:   getEnv("EUREKA_INSTANCE_HOST", "localhost"),
		EurekaInstancePort:   eurekaPort,
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
