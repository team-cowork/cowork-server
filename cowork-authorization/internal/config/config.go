package config

import (
	"fmt"
	mysqlDriver "github.com/go-sql-driver/mysql"
	"net"
	"os"
	"time"
)

const (
	defaultJWTAccessExpire        = "30m"
	defaultJWTRefreshExpire       = "2160h"
	defaultIdentityCommandTimeout = "5s"
)

type AppConfig struct {
	KafkaSecurity KafkaSecurity
	Port          string

	DBDSN string

	DataGSMClientID      string
	DataGSMTokenURL      string
	DataGSMUserInfoURL   string
	DataGSMWebhookSecret string
	RedisHost            string
	RedisPort            string

	KafkaBootstrapServers               string
	KafkaTopicUserSync                  string
	KafkaTopicUserIdentityCommand       string
	KafkaTopicUserIdentityCommandResult string
	KafkaTopicUserIdentityResultDLT     string
	KafkaGroupIDUserIdentityResult      string
	KafkaIdentityCommandTimeout         time.Duration
	KafkaTopicUserPresence              string

	JWTSecret        string
	JWTAccessExpire  time.Duration
	JWTRefreshExpire time.Duration
}

func Load() (*AppConfig, error) {
	security, err := loadKafkaSecurity()
	if err != nil {
		return nil, err
	}
	accessExpire, err := time.ParseDuration(getEnvOrDefault("JWT_ACCESS_EXPIRE", defaultJWTAccessExpire))
	if err != nil {
		return nil, fmt.Errorf("invalid JWT_ACCESS_EXPIRE: %w", err)
	}
	refreshExpire, err := time.ParseDuration(getEnvOrDefault("JWT_REFRESH_EXPIRE", defaultJWTRefreshExpire))
	if err != nil {
		return nil, fmt.Errorf("invalid JWT_REFRESH_EXPIRE: %w", err)
	}
	identityCommandTimeout, err := time.ParseDuration(getEnvOrDefault("KAFKA_IDENTITY_COMMAND_TIMEOUT", defaultIdentityCommandTimeout))
	if err != nil || identityCommandTimeout <= 0 {
		return nil, fmt.Errorf("invalid KAFKA_IDENTITY_COMMAND_TIMEOUT")
	}
	cfg := &AppConfig{
		KafkaSecurity:                       security,
		Port:                                getEnvOrDefault("PORT", "8081"),
		DataGSMClientID:                     os.Getenv("DATAGSM_CLIENT_ID"),
		DataGSMTokenURL:                     getEnvOrDefault("DATAGSM_TOKEN_URL", "https://oauth.authorization.datagsm.kr/v1/oauth/token"),
		DataGSMUserInfoURL:                  getEnvOrDefault("DATAGSM_USERINFO_URL", "https://oauth.resource.datagsm.kr/userinfo"),
		DataGSMWebhookSecret:                os.Getenv("DATAGSM_WEBHOOK_SECRET"),
		RedisHost:                           getEnvOrDefault("REDIS_HOST", "127.0.0.1"),
		RedisPort:                           getEnvOrDefault("REDIS_PORT", "6379"),
		KafkaBootstrapServers:               getEnvOrDefault("KAFKA_BOOTSTRAP_SERVERS", "localhost:9094"),
		KafkaTopicUserSync:                  getEnvOrDefault("KAFKA_TOPIC_USER_SYNC", "user.data.sync"),
		KafkaTopicUserIdentityCommand:       getEnvOrDefault("KAFKA_TOPIC_USER_IDENTITY_COMMAND", "user.identity.command"),
		KafkaTopicUserIdentityCommandResult: getEnvOrDefault("KAFKA_TOPIC_USER_IDENTITY_COMMAND_RESULT", "user.identity.command-result"),
		KafkaTopicUserIdentityResultDLT:     getEnvOrDefault("KAFKA_TOPIC_USER_IDENTITY_COMMAND_RESULT_DLT", "user.identity.command-result-dlt"),
		KafkaGroupIDUserIdentityResult:      getEnvOrDefault("KAFKA_GROUP_ID_USER_IDENTITY_COMMAND_RESULT", "cowork-authorization.user-identity-command-result"),
		KafkaIdentityCommandTimeout:         identityCommandTimeout,
		KafkaTopicUserPresence:              getEnvOrDefault("KAFKA_TOPIC_USER_PRESENCE", "user.presence.event"),
		JWTSecret:                           os.Getenv("JWT_SECRET"), JWTAccessExpire: accessExpire, JWTRefreshExpire: refreshExpire,
	}
	cfg.DBDSN, err = mysqlDSN("")
	if err != nil {
		return nil, err
	}
	if cfg.DBDSN != "" {
		cfg.DBDSN, err = normalizeMySQLDSNUTC(cfg.DBDSN)
		if err != nil {
			return nil, err
		}
	}
	if cfg.DBDSN == "" || cfg.JWTSecret == "" || cfg.DataGSMClientID == "" {
		return nil, fmt.Errorf("required configuration (DB_DSN, JWT_SECRET, DATAGSM_CLIENT_ID) is missing")
	}
	return cfg, nil
}

func getEnvOrDefault(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

// normalizeMySQLDSNUTC makes DATETIME(6) session and presence versions
// deterministic on both developer hosts and production containers. loc controls
// go-sql-driver encoding/scanning while time_zone controls MySQL defaults and
// comparisons such as CURRENT_TIMESTAMP.
func normalizeMySQLDSNUTC(dsn string) (string, error) {
	cfg, err := mysqlDriver.ParseDSN(dsn)
	if err != nil {
		return "", fmt.Errorf("invalid DB_DSN: %w", err)
	}
	cfg.ParseTime = true
	cfg.Loc = time.UTC
	if cfg.Params == nil {
		cfg.Params = make(map[string]string)
	}
	cfg.Params["time_zone"] = "'+00:00'"
	return cfg.FormatDSN(), nil
}

// Individual database inputs define one connection and take precedence over a stored DSN.
func mysqlDSN(fallback string) (string, error) {
	keys := []string{"DB_HOST", "DB_PORT", "DB_NAME", "DB_USERNAME", "DB_PASSWORD"}
	configured := false
	for _, key := range keys {
		if _, present := os.LookupEnv(key); present {
			configured = true
			break
		}
	}
	if !configured {
		return getEnvOrDefault("DB_DSN", fallback), nil
	}
	for _, key := range keys {
		if os.Getenv(key) == "" {
			return "", fmt.Errorf("required configuration %q is not set", key)
		}
	}
	cfg := mysqlDriver.NewConfig()
	cfg.User = os.Getenv("DB_USERNAME")
	cfg.Passwd = os.Getenv("DB_PASSWORD")
	cfg.Net = "tcp"
	cfg.Addr = net.JoinHostPort(os.Getenv("DB_HOST"), os.Getenv("DB_PORT"))
	cfg.DBName = os.Getenv("DB_NAME")
	cfg.ParseTime = true
	cfg.Loc = time.Local
	cfg.Params = map[string]string{"charset": "utf8mb4"}
	return cfg.FormatDSN(), nil
}
