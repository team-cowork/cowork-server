package config

import (
	"context"
	"fmt"
	"log/slog"
	"os"
	"strconv"
	"time"

	"github.com/cowork/authorization/internal/config/springconfig"
)

const (
	defaultJWTAccessExpire  = "30m"
	defaultJWTRefreshExpire = "2160h"
)

type AppConfig struct {
	Port string

	DBDSN string

	DataGSMClientID    string
	DataGSMTokenURL    string
	DataGSMUserInfoURL string

	JWTSecret        string
	JWTAccessExpire  time.Duration
	JWTRefreshExpire time.Duration

	EurekaServerURL    string
	EurekaAppName      string
	EurekaInstanceHost string
	EurekaInstancePort int

	UserServiceURL string
}

func Load() (*AppConfig, error) {
	flatMap, err := fetchFromConfigServer()
	if err != nil {
		return nil, err
	}

	accessExpire, err := time.ParseDuration(lookup(flatMap, "JWT_ACCESS_EXPIRE", defaultJWTAccessExpire))
	if err != nil {
		return nil, fmt.Errorf("invalid JWT_ACCESS_EXPIRE: %w", err)
	}

	refreshExpire, err := time.ParseDuration(lookup(flatMap, "JWT_REFRESH_EXPIRE", defaultJWTRefreshExpire))
	if err != nil {
		return nil, fmt.Errorf("invalid JWT_REFRESH_EXPIRE: %w", err)
	}

	eurekaPort, err := strconv.Atoi(lookup(flatMap, "EUREKA_INSTANCE_PORT", "8081"))
	if err != nil {
		return nil, fmt.Errorf("invalid EUREKA_INSTANCE_PORT: %w", err)
	}

	cfg := &AppConfig{
		Port:  lookup(flatMap, "PORT", "8081"),
		DBDSN: lookup(flatMap, "DB_DSN", ""),

		DataGSMClientID:    lookup(flatMap, "DATAGSM_CLIENT_ID", ""),
		DataGSMTokenURL:    lookup(flatMap, "DATAGSM_TOKEN_URL", ""),
		DataGSMUserInfoURL: lookup(flatMap, "DATAGSM_USERINFO_URL", ""),

		JWTSecret:        lookup(flatMap, "JWT_SECRET", ""),
		JWTAccessExpire:  accessExpire,
		JWTRefreshExpire: refreshExpire,

		EurekaServerURL:    lookup(flatMap, "EUREKA_SERVER_URL", "http://localhost:8761/eureka"),
		EurekaAppName:      lookup(flatMap, "EUREKA_APP_NAME", "cowork-authorization"),
		EurekaInstanceHost: lookup(flatMap, "EUREKA_INSTANCE_HOST", "localhost"),
		EurekaInstancePort: eurekaPort,

		UserServiceURL: lookup(flatMap, "USER_SERVICE_URL", "http://cowork-user:8082"),
	}

	overrideFromEnv(cfg)
	if cfg.DBDSN == "" || cfg.JWTSecret == "" || cfg.DataGSMClientID == "" {
		return nil, fmt.Errorf("required configuration (DB_DSN, JWT_SECRET, DATAGSM_CLIENT_ID) is missing")
	}
	return cfg, nil
}

func fetchFromConfigServer() (map[string]string, error) {
	configURL := os.Getenv("APP_CONFIG_URL")
	if configURL == "" {
		return map[string]string{}, nil
	}

	profile := getEnvOrDefault("APP_PROFILE", "local")
	client := springconfig.NewClient(configURL, "cowork-authorization", profile)

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	flatMap, err := client.Fetch(ctx)
	if err != nil {
		if profile == "prod" {
			return nil, fmt.Errorf("config server unreachable in prod profile: %w", err)
		}
		slog.Warn("config server unavailable, falling back to env vars only", "err", err)
		return map[string]string{}, nil
	}

	slog.Info("config loaded from config server", "profile", profile, "keys", len(flatMap))
	return flatMap, nil
}

func overrideFromEnv(cfg *AppConfig) {
	if v := os.Getenv("PORT"); v != "" {
		cfg.Port = v
	}
	if v := os.Getenv("DB_DSN"); v != "" {
		cfg.DBDSN = v
	}
	if v := os.Getenv("DATAGSM_CLIENT_ID"); v != "" {
		cfg.DataGSMClientID = v
	}
	if v := os.Getenv("DATAGSM_TOKEN_URL"); v != "" {
		cfg.DataGSMTokenURL = v
	}
	if v := os.Getenv("DATAGSM_USERINFO_URL"); v != "" {
		cfg.DataGSMUserInfoURL = v
	}
	if v := os.Getenv("JWT_SECRET"); v != "" {
		cfg.JWTSecret = v
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
	}
	if v := os.Getenv("USER_SERVICE_URL"); v != "" {
		cfg.UserServiceURL = v
	}
}

func getEnvOrDefault(key, defaultVal string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return defaultVal
}

func lookup(flatMap map[string]string, key, fallback string) string {
	if v, ok := flatMap[key]; ok && v != "" {
		return v
	}
	return fallback
}
