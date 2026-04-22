package config

import (
	"fmt"
	"os"
	"strconv"
	"time"
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
	accessExpire, err := time.ParseDuration(getEnvOrDefault("JWT_ACCESS_EXPIRE", "15m"))
	if err != nil {
		return nil, fmt.Errorf("invalid JWT_ACCESS_EXPIRE: %w", err)
	}

	refreshExpire, err := time.ParseDuration(getEnvOrDefault("JWT_REFRESH_EXPIRE", "168h"))
	if err != nil {
		return nil, fmt.Errorf("invalid JWT_REFRESH_EXPIRE: %w", err)
	}

	eurekaPort, err := strconv.Atoi(getEnvOrDefault("EUREKA_INSTANCE_PORT", "8081"))
	if err != nil {
		return nil, fmt.Errorf("invalid EUREKA_INSTANCE_PORT: %w", err)
	}

	cfg := &AppConfig{
		Port:  getEnvOrDefault("PORT", "8081"),
		DBDSN: mustGetEnv("DB_DSN"),

		DataGSMClientID:    mustGetEnv("DATAGSM_CLIENT_ID"),
		DataGSMTokenURL:    mustGetEnv("DATAGSM_TOKEN_URL"),
		DataGSMUserInfoURL: mustGetEnv("DATAGSM_USERINFO_URL"),

		JWTSecret:        mustGetEnv("JWT_SECRET"),
		JWTAccessExpire:  accessExpire,
		JWTRefreshExpire: refreshExpire,

		EurekaServerURL:    getEnvOrDefault("EUREKA_SERVER_URL", "http://localhost:8761/eureka"),
		EurekaAppName:      getEnvOrDefault("EUREKA_APP_NAME", "cowork-authorization"),
		EurekaInstanceHost: getEnvOrDefault("EUREKA_INSTANCE_HOST", "localhost"),
		EurekaInstancePort: eurekaPort,

		UserServiceURL: getEnvOrDefault("USER_SERVICE_URL", "http://cowork-user:8080"),
	}

	return cfg, nil
}

func mustGetEnv(key string) string {
	v := os.Getenv(key)
	if v == "" {
		panic(fmt.Sprintf("required environment variable %q is not set", key))
	}
	return v
}

func getEnvOrDefault(key, defaultVal string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return defaultVal
}
