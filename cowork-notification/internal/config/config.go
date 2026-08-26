package config

import (
	"context"
	"fmt"
	"log/slog"
	"net"
	"os"
	"strconv"
	"time"

	"github.com/cowork/cowork-notification/internal/config/springconfig"
)

type AppConfig struct {
	Port                          string
	DBDSN                         string
	KafkaBrokers                  string
	KafkaTopicNotify              string
	KafkaGroupID                  string
	KafkaProjectionGroupID        string
	KafkaTopicChannelNotification string
	KafkaTopicUserProfile         string
	KafkaTopicTeamLifecycle       string
	FCMCredentialsFile            string
	EurekaServerURL               string
	EurekaAppName                 string
	EurekaInstanceHost            string
	EurekaInstanceID              string
	EurekaInstancePort            int
}

func Load() (*AppConfig, error) {
	flatMap, err := fetchFromConfigServer()
	if err != nil {
		return nil, err
	}

	cfg := &AppConfig{
		Port:                          lookup(flatMap, "server.port", "8086"),
		DBDSN:                         lookup(flatMap, "db.dsn", ""),
		KafkaBrokers:                  lookup(flatMap, "kafka.brokers", ""),
		KafkaTopicNotify:              lookup(flatMap, "kafka.topic", "notification.trigger"),
		KafkaGroupID:                  lookup(flatMap, "kafka.group-id", "cowork-notification"),
		KafkaProjectionGroupID:        lookup(flatMap, "kafka.projection-group-id", "cowork-notification-projections"),
		KafkaTopicChannelNotification: lookup(flatMap, "kafka.topics.channel-notification", "preference.channel-notification.changed"),
		KafkaTopicUserProfile:         lookup(flatMap, "kafka.topics.user-profile", "user.profile.event"),
		KafkaTopicTeamLifecycle:       lookup(flatMap, "kafka.topics.team-lifecycle", "team.lifecycle"),
		FCMCredentialsFile:            lookup(flatMap, "fcm.credentials-file", ""),
		EurekaServerURL:               lookup(flatMap, "eureka.server-url", "http://localhost:8761/eureka"),
		EurekaAppName:                 lookup(flatMap, "eureka.app-name", "cowork-notification"),
		EurekaInstanceHost:            lookup(flatMap, "eureka.instance.host", "localhost"),
	}

	portStr := lookup(flatMap, "eureka.instance.port", "8086")

	overrideFromEnv(cfg, &portStr)

	eurekaPort, err := strconv.Atoi(portStr)
	if err != nil {
		return nil, fmt.Errorf("invalid eureka instance port %q: %w", portStr, err)
	}
	cfg.EurekaInstancePort = eurekaPort
	if err := configureEurekaIdentity(cfg); err != nil {
		return nil, err
	}

	return validate(cfg)
}

func fetchFromConfigServer() (map[string]string, error) {
	configURL := os.Getenv("APP_CONFIG_URL")
	if configURL == "" {
		return map[string]string{}, nil
	}

	profile := getEnv("APP_PROFILE", "local")
	client := springconfig.NewClient(configURL, "cowork-notification", profile)

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	flatMap, err := client.Fetch(ctx)
	if err != nil {
		return nil, fmt.Errorf("config server unreachable for profile %s: %w", profile, err)
	}

	slog.Info("config loaded from config server", "profile", profile, "keys", len(flatMap))
	return flatMap, nil
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
	if v := os.Getenv("KAFKA_PROJECTION_GROUP_ID"); v != "" {
		cfg.KafkaProjectionGroupID = v
	}
	if v := os.Getenv("KAFKA_TOPIC_CHANNEL_NOTIFICATION_PREFERENCE"); v != "" {
		cfg.KafkaTopicChannelNotification = v
	}
	if v := os.Getenv("KAFKA_TOPIC_USER_PROFILE"); v != "" {
		cfg.KafkaTopicUserProfile = v
	}
	if v := os.Getenv("KAFKA_TOPIC_TEAM_LIFECYCLE"); v != "" {
		cfg.KafkaTopicTeamLifecycle = v
	}
	if v := os.Getenv("FCM_CREDENTIALS_FILE"); v != "" {
		cfg.FCMCredentialsFile = v
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

func validate(cfg *AppConfig) (*AppConfig, error) {
	required := map[string]string{
		"DB_DSN (or db.dsn from config server)":                                       cfg.DBDSN,
		"KAFKA_BROKERS (or kafka.brokers from config server)":                         cfg.KafkaBrokers,
		"KAFKA_TOPIC_NOTIFICATION (or kafka.topic from config server)":                cfg.KafkaTopicNotify,
		"KAFKA_GROUP_ID (or kafka.group-id from config server)":                       cfg.KafkaGroupID,
		"KAFKA_PROJECTION_GROUP_ID (or kafka.projection-group-id from config server)": cfg.KafkaProjectionGroupID,
		"FCM_CREDENTIALS_FILE (or fcm.credentials-file from config server)":           cfg.FCMCredentialsFile,
	}
	for name, val := range required {
		if val == "" {
			return nil, fmt.Errorf("required config %q is not set", name)
		}
	}

	if _, err := os.Stat(cfg.FCMCredentialsFile); err != nil {
		return nil, fmt.Errorf("fcm credentials file %q is not accessible: %w", cfg.FCMCredentialsFile, err)
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
