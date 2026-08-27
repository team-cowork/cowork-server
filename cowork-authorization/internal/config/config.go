package config

import (
	"context"
	"fmt"
	"log/slog"
	"net"
	"os"
	"strconv"
	"time"

	"github.com/cowork/authorization/internal/config/springconfig"
	mysqlDriver "github.com/go-sql-driver/mysql"
)

const (
	defaultJWTAccessExpire        = "30m"
	defaultJWTRefreshExpire       = "2160h"
	defaultIdentityCommandTimeout = "5s"
)

type AppConfig struct {
	Port string

	DBDSN string

	DataGSMClientID      string
	DataGSMTokenURL      string
	DataGSMUserInfoURL   string
	DataGSMWebhookSecret string

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

	EurekaServerURL    string
	EurekaAppName      string
	EurekaInstanceHost string
	EurekaInstanceID   string
	EurekaInstancePort int
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
	identityTimeoutValue := lookup(flatMap, "KAFKA_IDENTITY_COMMAND_TIMEOUT", defaultIdentityCommandTimeout)
	if value := os.Getenv("KAFKA_IDENTITY_COMMAND_TIMEOUT"); value != "" {
		identityTimeoutValue = value
	}
	identityCommandTimeout, err := time.ParseDuration(identityTimeoutValue)
	if err != nil || identityCommandTimeout <= 0 {
		return nil, fmt.Errorf("invalid KAFKA_IDENTITY_COMMAND_TIMEOUT")
	}

	eurekaPort, err := strconv.Atoi(lookup(flatMap, "EUREKA_INSTANCE_PORT", "8081"))
	if err != nil {
		return nil, fmt.Errorf("invalid EUREKA_INSTANCE_PORT: %w", err)
	}

	cfg := &AppConfig{
		Port:  lookup(flatMap, "PORT", "8081"),
		DBDSN: lookup(flatMap, "DB_DSN", ""),

		DataGSMClientID:      lookup(flatMap, "DATAGSM_CLIENT_ID", ""),
		DataGSMTokenURL:      lookup(flatMap, "DATAGSM_TOKEN_URL", ""),
		DataGSMUserInfoURL:   lookup(flatMap, "DATAGSM_USERINFO_URL", ""),
		DataGSMWebhookSecret: lookup(flatMap, "DATAGSM_WEBHOOK_SECRET", ""),

		KafkaBootstrapServers:               lookup(flatMap, "KAFKA_BOOTSTRAP_SERVERS", "localhost:9094"),
		KafkaTopicUserSync:                  lookup(flatMap, "KAFKA_TOPIC_USER_SYNC", "user.data.sync"),
		KafkaTopicUserIdentityCommand:       lookup(flatMap, "KAFKA_TOPIC_USER_IDENTITY_COMMAND", "user.identity.command"),
		KafkaTopicUserIdentityCommandResult: lookup(flatMap, "KAFKA_TOPIC_USER_IDENTITY_COMMAND_RESULT", "user.identity.command-result"),
		KafkaTopicUserIdentityResultDLT:     lookup(flatMap, "KAFKA_TOPIC_USER_IDENTITY_COMMAND_RESULT_DLT", "user.identity.command-result-dlt"),
		KafkaGroupIDUserIdentityResult:      lookup(flatMap, "KAFKA_GROUP_ID_USER_IDENTITY_COMMAND_RESULT", "cowork-authorization.user-identity-command-result"),
		KafkaIdentityCommandTimeout:         identityCommandTimeout,
		KafkaTopicUserPresence:              lookup(flatMap, "KAFKA_TOPIC_USER_PRESENCE", "user.presence.event"),

		JWTSecret:        lookup(flatMap, "JWT_SECRET", ""),
		JWTAccessExpire:  accessExpire,
		JWTRefreshExpire: refreshExpire,

		EurekaServerURL:    lookup(flatMap, "EUREKA_SERVER_URL", "http://localhost:8761/eureka"),
		EurekaAppName:      lookup(flatMap, "EUREKA_APP_NAME", "cowork-authorization"),
		EurekaInstanceHost: lookup(flatMap, "EUREKA_INSTANCE_HOST", "localhost"),
		EurekaInstancePort: eurekaPort,
	}

	overrideFromEnv(cfg)
	if cfg.DBDSN != "" {
		cfg.DBDSN, err = normalizeMySQLDSNUTC(cfg.DBDSN)
		if err != nil {
			return nil, err
		}
	}
	if err := configureEurekaIdentity(cfg); err != nil {
		return nil, err
	}
	if cfg.DBDSN == "" || cfg.JWTSecret == "" || cfg.DataGSMClientID == "" {
		return nil, fmt.Errorf("required configuration (DB_DSN, JWT_SECRET, DATAGSM_CLIENT_ID) is missing")
	}
	return cfg, nil
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
		return nil, fmt.Errorf("config server unreachable for profile %s: %w", profile, err)
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
	if v := os.Getenv("DATAGSM_WEBHOOK_SECRET"); v != "" {
		cfg.DataGSMWebhookSecret = v
	}
	if v := os.Getenv("KAFKA_BOOTSTRAP_SERVERS"); v != "" {
		cfg.KafkaBootstrapServers = v
	}
	if v := os.Getenv("KAFKA_TOPIC_USER_SYNC"); v != "" {
		cfg.KafkaTopicUserSync = v
	}
	if v := os.Getenv("KAFKA_TOPIC_USER_IDENTITY_COMMAND"); v != "" {
		cfg.KafkaTopicUserIdentityCommand = v
	}
	if v := os.Getenv("KAFKA_TOPIC_USER_IDENTITY_COMMAND_RESULT"); v != "" {
		cfg.KafkaTopicUserIdentityCommandResult = v
	}
	if v := os.Getenv("KAFKA_TOPIC_USER_IDENTITY_COMMAND_RESULT_DLT"); v != "" {
		cfg.KafkaTopicUserIdentityResultDLT = v
	}
	if v := os.Getenv("KAFKA_GROUP_ID_USER_IDENTITY_COMMAND_RESULT"); v != "" {
		cfg.KafkaGroupIDUserIdentityResult = v
	}
	if v := os.Getenv("KAFKA_TOPIC_USER_PRESENCE"); v != "" {
		cfg.KafkaTopicUserPresence = v
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
}

func configureEurekaIdentity(cfg *AppConfig) error {
	runtimeIdentity := os.Getenv("EUREKA_USE_RUNTIME_HOSTNAME") == "true"
	identityHost := cfg.EurekaInstanceHost
	if runtimeIdentity {
		hostname, err := os.Hostname()
		if err != nil {
			return fmt.Errorf("resolve runtime hostname for Eureka instance id: %w", err)
		}
		if hostname == "" {
			return fmt.Errorf("resolve runtime hostname for Eureka instance id: empty hostname")
		}
		identityHost = hostname
		if explicitHost := os.Getenv("EUREKA_INSTANCE_HOST"); explicitHost != "" {
			cfg.EurekaInstanceHost = explicitHost
		} else if address := firstNonLoopbackIPv4(); address != "" {
			cfg.EurekaInstanceHost = address
		} else {
			return fmt.Errorf("resolve non-loopback address for Eureka registration")
		}
	}
	if explicitID := os.Getenv("EUREKA_INSTANCE_ID"); explicitID != "" {
		cfg.EurekaInstanceID = explicitID
	} else {
		cfg.EurekaInstanceID = fmt.Sprintf("%s:%s:%d", identityHost, cfg.EurekaAppName, cfg.EurekaInstancePort)
	}
	return nil
}

func firstNonLoopbackIPv4() string {
	addresses, err := net.InterfaceAddrs()
	if err != nil {
		return ""
	}
	for _, address := range addresses {
		ip, _, err := net.ParseCIDR(address.String())
		if err == nil && ip.To4() != nil && !ip.IsLoopback() && !ip.IsLinkLocalUnicast() {
			return ip.String()
		}
	}
	return ""
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
