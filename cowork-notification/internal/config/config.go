package config

import (
	"context"
	"fmt"
	"log/slog"
	"os"
	"strconv"
	"time"

	"github.com/cowork/cowork-notification/internal/config/springconfig"
)

type AppConfig struct {
	Port                 string
	DBDSN                string
	KafkaBrokers         string
	KafkaTopicNotify     string
	KafkaGroupID         string
	FCMCredentialsFile   string
	PreferenceServiceURL string
	TeamServiceURL       string
	UserServiceURL       string
	EurekaServerURL      string
	EurekaAppName        string
	EurekaInstanceHost   string
	EurekaInstancePort   int
}

func Load() (*AppConfig, error) {
	flatMap := fetchFromConfigServer()

	cfg := &AppConfig{
		Port:                 lookup(flatMap, "server.port", "8086"),
		DBDSN:                lookup(flatMap, "db.dsn", ""),
		KafkaBrokers:         lookup(flatMap, "kafka.brokers", ""),
		KafkaTopicNotify:     lookup(flatMap, "kafka.topic", ""),
		KafkaGroupID:         lookup(flatMap, "kafka.group-id", ""),
		FCMCredentialsFile:   lookup(flatMap, "fcm.credentials-file", ""),
		PreferenceServiceURL: lookup(flatMap, "preference.service-url", ""),
		TeamServiceURL:       lookup(flatMap, "team.service-url", ""),
		UserServiceURL:       lookup(flatMap, "user.service-url", ""),
		EurekaServerURL:      lookup(flatMap, "eureka.server-url", "http://localhost:8761/eureka"),
		EurekaAppName:        lookup(flatMap, "eureka.app-name", "cowork-notification"),
		EurekaInstanceHost:   lookup(flatMap, "eureka.instance.host", "localhost"),
	}

	portStr := lookup(flatMap, "eureka.instance.port", "8086")

	overrideFromEnv(cfg, &portStr)

	eurekaPort, err := strconv.Atoi(portStr)
	if err != nil {
		return nil, fmt.Errorf("invalid eureka instance port %q: %w", portStr, err)
	}
	cfg.EurekaInstancePort = eurekaPort

	return validate(cfg)
}

func fetchFromConfigServer() map[string]string {
	configURL := os.Getenv("APP_CONFIG_URL")
	if configURL == "" {
		return map[string]string{}
	}

	profile := getEnv("APP_PROFILE", "dev")
	client := springconfig.NewClient(configURL, "cowork-notification", profile)

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

func overrideFromEnv(cfg *AppConfig, eurekaPortStr *string) {
	if v := os.Getenv("PORT"); v != "" {
		cfg.Port = v
	}
	if v := os.Getenv("DB_DSN"); v != "" {
		cfg.DBDSN = v
	}
	if v := os.Getenv("KAFKA_BROKERS"); v != "" {
		cfg.KafkaBrokers = v
	}
	if v := os.Getenv("KAFKA_TOPIC_NOTIFICATION"); v != "" {
		cfg.KafkaTopicNotify = v
	}
	if v := os.Getenv("KAFKA_GROUP_ID"); v != "" {
		cfg.KafkaGroupID = v
	}
	if v := os.Getenv("FCM_CREDENTIALS_FILE"); v != "" {
		cfg.FCMCredentialsFile = v
	}
	if v := os.Getenv("PREFERENCE_SERVICE_URL"); v != "" {
		cfg.PreferenceServiceURL = v
	}
	if v := os.Getenv("TEAM_SERVICE_URL"); v != "" {
		cfg.TeamServiceURL = v
	}
	if v := os.Getenv("USER_SERVICE_URL"); v != "" {
		cfg.UserServiceURL = v
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
		*eurekaPortStr = v
	}
}

func validate(cfg *AppConfig) (*AppConfig, error) {
	required := map[string]string{
		"DB_DSN (or db.dsn from config server)":                             cfg.DBDSN,
		"KAFKA_BROKERS (or kafka.brokers from config server)":               cfg.KafkaBrokers,
		"KAFKA_TOPIC_NOTIFICATION (or kafka.topic from config server)":      cfg.KafkaTopicNotify,
		"KAFKA_GROUP_ID (or kafka.group-id from config server)":             cfg.KafkaGroupID,
		"FCM_CREDENTIALS_FILE (or fcm.credentials-file from config server)": cfg.FCMCredentialsFile,
		"PREFERENCE_SERVICE_URL (or preference.service-url from config server)": cfg.PreferenceServiceURL,
		"TEAM_SERVICE_URL (or team.service-url from config server)":             cfg.TeamServiceURL,
		"USER_SERVICE_URL (or user.service-url from config server)":             cfg.UserServiceURL,
	}
	for name, val := range required {
		if val == "" {
			return nil, fmt.Errorf("required config %q is not set", name)
		}
	}
	return cfg, nil
}

func lookup(flatMap map[string]string, key, fallback string) string {
	if v, ok := flatMap[key]; ok && v != "" {
		return v
	}
	return fallback
}

func getEnv(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}
