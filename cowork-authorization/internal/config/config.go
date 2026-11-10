package config

import (
	"fmt"
	"net"
	"net/url"
	"os"
	"strings"
	"time"

	mysqlDriver "github.com/go-sql-driver/mysql"
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

	DataGSMClientID         string
	DataGSMAuthorizationURL string
	DataGSMResourceURL      string
	DataGSMRedirectURIs     []string
	DataGSMWebhookSecret    string
	RedisHost               string
	RedisPort               string

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
		DataGSMAuthorizationURL:             getEnvOrDefault("DATAGSM_AUTHORIZATION_BASE_URL", "https://oauth.authorization.datagsm.kr"),
		DataGSMResourceURL:                  getEnvOrDefault("DATAGSM_RESOURCE_BASE_URL", "https://oauth.resource.datagsm.kr"),
		DataGSMWebhookSecret:                os.Getenv("DATAGSM_WEBHOOK_SECRET"),
		RedisHost:                           os.Getenv("REDIS_HOST"),
		RedisPort:                           os.Getenv("REDIS_PORT"),
		KafkaBootstrapServers:               os.Getenv("KAFKA_BOOTSTRAP_SERVERS"),
		KafkaTopicUserSync:                  getEnvOrDefault("KAFKA_TOPIC_USER_SYNC", "user.data.sync"),
		KafkaTopicUserIdentityCommand:       getEnvOrDefault("KAFKA_TOPIC_USER_IDENTITY_COMMAND", "user.identity.command.v2"),
		KafkaTopicUserIdentityCommandResult: getEnvOrDefault("KAFKA_TOPIC_USER_IDENTITY_COMMAND_RESULT", "user.identity.command-result"),
		KafkaTopicUserIdentityResultDLT:     getEnvOrDefault("KAFKA_TOPIC_USER_IDENTITY_COMMAND_RESULT_DLT", "user.identity.command-result-dlt"),
		KafkaGroupIDUserIdentityResult:      getEnvOrDefault("KAFKA_GROUP_ID_USER_IDENTITY_COMMAND_RESULT", "cowork-authorization.user-identity-command-result"),
		KafkaIdentityCommandTimeout:         identityCommandTimeout,
		KafkaTopicUserPresence:              getEnvOrDefault("KAFKA_TOPIC_USER_PRESENCE", "user.presence.event"),
		JWTSecret:                           os.Getenv("JWT_SECRET"), JWTAccessExpire: accessExpire, JWTRefreshExpire: refreshExpire,
	}
	cfg.DataGSMRedirectURIs, err = parseRedirectURIs(os.Getenv("DATAGSM_REDIRECT_URIS"))
	if err != nil {
		return nil, err
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
	for name, value := range map[string]string{
		"DB_DSN":                  cfg.DBDSN,
		"JWT_SECRET":              cfg.JWTSecret,
		"DATAGSM_CLIENT_ID":       cfg.DataGSMClientID,
		"DATAGSM_WEBHOOK_SECRET":  cfg.DataGSMWebhookSecret,
		"REDIS_HOST":              cfg.RedisHost,
		"REDIS_PORT":              cfg.RedisPort,
		"KAFKA_BOOTSTRAP_SERVERS": cfg.KafkaBootstrapServers,
	} {
		if value == "" {
			return nil, fmt.Errorf("required configuration %q is not set", name)
		}
	}
	return cfg, nil
}

func parseRedirectURIs(raw string) ([]string, error) {
	var result []string
	seen := make(map[string]bool)
	for _, value := range strings.Split(raw, ",") {
		value = strings.TrimSpace(value)
		if value == "" {
			continue
		}
		u, err := url.Parse(value)
		if err != nil || u.Scheme == "" || u.Host == "" || u.User != nil || u.Fragment != "" || u.RawQuery != "" ||
			(u.Scheme == "http" && u.Hostname() != "localhost" && !net.ParseIP(u.Hostname()).IsLoopback()) ||
			(u.Scheme != "https" && u.Scheme != "http" && u.Scheme != "cowork") {
			return nil, fmt.Errorf("DATAGSM_REDIRECT_URIS must contain registered HTTPS, loopback HTTP, or cowork callback URLs")
		}
		if !seen[value] {
			result = append(result, value)
			seen[value] = true
		}
	}
	if len(result) == 0 {
		return nil, fmt.Errorf("DATAGSM_REDIRECT_URIS must contain at least one registered callback URL")
	}
	return result, nil
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
