package config

import (
	"context"
	"fmt"
	"log/slog"
	"net"
	"net/url"
	"os"
	"strconv"
	"time"

	"github.com/cowork/cowork-voice/internal/config/springconfig"
)

type AppConfig struct {
	Port                        string
	MongoDBURI                  string
	MongoDBDB                   string
	LiveKitURL                  string
	LiveKitWsURL                string
	LiveKitAPIKey               string
	LiveKitAPISecret            string
	LiveKitTokenTTLSecs         int64
	KafkaBrokers                string
	KafkaTopicVoiceEvent        string
	KafkaGroupIDChannelAccess   string
	KafkaMessageTimeoutMs       int
	EurekaEnabled               bool
	EurekaServerURL             string
	EurekaAppName               string
	EurekaInstanceHost          string
	EurekaInstanceID            string
	EurekaInstancePort          int
	EurekaHeartbeatIntervalSecs int
}

func Load() (*AppConfig, error) {
	flatMap, err := fetchFromConfigServer()
	if err != nil {
		return nil, err
	}

	mongoURI, mongoDB, err := mongoConfig(flatMap)
	if err != nil {
		return nil, err
	}
	liveKitURL, err := requireConfig(flatMap, "LIVEKIT_URL")
	if err != nil {
		return nil, err
	}
	liveKitWsURL, err := requireConfig(flatMap, "LIVEKIT_WS_URL")
	if err != nil {
		return nil, err
	}
	liveKitAPIKey, err := requireConfig(flatMap, "LIVEKIT_API_KEY")
	if err != nil {
		return nil, err
	}
	liveKitAPISecret, err := requireConfig(flatMap, "LIVEKIT_API_SECRET")
	if err != nil {
		return nil, err
	}
	kafkaBrokers := getEnv("KAFKA_BOOTSTRAP_SERVERS", lookup(flatMap, "KAFKA_BOOTSTRAP_SERVERS", ""))
	if kafkaBrokers == "" {
		return nil, fmt.Errorf("required configuration KAFKA_BOOTSTRAP_SERVERS is not set")
	}
	kafkaTopic, err := requireConfig(flatMap, "KAFKA_TOPIC_VOICE_EVENT")
	if err != nil {
		return nil, err
	}
	ttlSecs, err := strconv.ParseInt(lookup(flatMap, "LIVEKIT_TOKEN_TTL_SECS", "3600"), 10, 64)
	if err != nil {
		return nil, fmt.Errorf("invalid LIVEKIT_TOKEN_TTL_SECS: %w", err)
	}

	timeoutMs, err := strconv.Atoi(lookup(flatMap, "KAFKA_MESSAGE_TIMEOUT_MS", "5000"))
	if err != nil {
		return nil, fmt.Errorf("invalid KAFKA_MESSAGE_TIMEOUT_MS: %w", err)
	}

	eurekaPortValue := getEnv("PORT", lookup(flatMap, "APP_ADVERTISE_PORT", lookup(flatMap, "PORT", "8089")))
	eurekaPortValue = getEnv("APP_ADVERTISE_PORT", eurekaPortValue)
	eurekaPort, err := strconv.Atoi(eurekaPortValue)
	if err != nil {
		return nil, fmt.Errorf("invalid Eureka advertised port")
	}

	heartbeatIntervalSecs, err := strconv.Atoi(lookup(flatMap, "EUREKA_HEARTBEAT_INTERVAL_SECS", "10"))
	if err != nil {
		return nil, fmt.Errorf("invalid EUREKA_HEARTBEAT_INTERVAL_SECS: %w", err)
	}

	cfg := &AppConfig{
		Port:                        lookup(flatMap, "PORT", "8089"),
		MongoDBURI:                  mongoURI,
		MongoDBDB:                   mongoDB,
		LiveKitURL:                  liveKitURL,
		LiveKitWsURL:                liveKitWsURL,
		LiveKitAPIKey:               liveKitAPIKey,
		LiveKitAPISecret:            liveKitAPISecret,
		LiveKitTokenTTLSecs:         ttlSecs,
		KafkaBrokers:                kafkaBrokers,
		KafkaTopicVoiceEvent:        kafkaTopic,
		KafkaGroupIDChannelAccess:   lookup(flatMap, "KAFKA_GROUP_ID_CHANNEL_ACCESS", "cowork-voice.channel-permissions.v1"),
		KafkaMessageTimeoutMs:       timeoutMs,
		EurekaEnabled:               lookup(flatMap, "EUREKA_ENABLED", "true") != "false",
		EurekaServerURL:             lookup(flatMap, "EUREKA_SERVER_URL", "http://localhost:8761/eureka"),
		EurekaAppName:               lookup(flatMap, "EUREKA_APP_NAME", "cowork-voice"),
		EurekaInstanceHost:          lookup(flatMap, "APP_ADVERTISE_ADDRESS", "localhost"),
		EurekaInstancePort:          eurekaPort,
		EurekaHeartbeatIntervalSecs: heartbeatIntervalSecs,
	}

	overrideFromEnv(cfg)
	if err := configureEurekaIdentity(cfg); err != nil {
		return nil, err
	}
	return cfg, nil
}

func fetchFromConfigServer() (map[string]string, error) {
	configURL := os.Getenv("CONFIG_SERVER_URL")
	if configURL == "" {
		return map[string]string{}, nil
	}

	profile := getEnv("APP_CONFIG_PROFILE", "local")
	client := springconfig.NewClient(configURL, "cowork-voice", profile)

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	flatMap, err := client.Fetch(ctx)
	if err != nil {
		return nil, fmt.Errorf("config server unreachable for profile %s: %w", profile, err)
	}

	slog.Info("config loaded from config server", "profile", profile, "keys", len(flatMap))
	return flatMap, nil
}

func getEnv(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

func lookup(flatMap map[string]string, key, fallback string) string {
	if v, ok := flatMap[key]; ok && v != "" {
		return v
	}
	return fallback
}

func requireConfig(flatMap map[string]string, key string) (string, error) {
	if v := getEnv(key, lookup(flatMap, key, "")); v != "" {
		return v, nil
	}
	return "", fmt.Errorf("required configuration %q is not set", key)
}

func overrideFromEnv(cfg *AppConfig) {
	if v := os.Getenv("PORT"); v != "" {
		cfg.Port = v
	}
	if v := os.Getenv("LIVEKIT_URL"); v != "" {
		cfg.LiveKitURL = v
	}
	if v := os.Getenv("LIVEKIT_WS_URL"); v != "" {
		cfg.LiveKitWsURL = v
	}
	if v := os.Getenv("LIVEKIT_API_KEY"); v != "" {
		cfg.LiveKitAPIKey = v
	}
	if v := os.Getenv("LIVEKIT_API_SECRET"); v != "" {
		cfg.LiveKitAPISecret = v
	}
	if v := os.Getenv("LIVEKIT_TOKEN_TTL_SECS"); v != "" {
		if parsed, err := strconv.ParseInt(v, 10, 64); err == nil {
			cfg.LiveKitTokenTTLSecs = parsed
		}
	}
	if v := os.Getenv("KAFKA_BOOTSTRAP_SERVERS"); v != "" {
		cfg.KafkaBrokers = v
	}
	if v := os.Getenv("KAFKA_TOPIC_VOICE_EVENT"); v != "" {
		cfg.KafkaTopicVoiceEvent = v
	}

	if v := os.Getenv("KAFKA_GROUP_ID_CHANNEL_ACCESS"); v != "" {
		cfg.KafkaGroupIDChannelAccess = v
	}
	if v := os.Getenv("KAFKA_MESSAGE_TIMEOUT_MS"); v != "" {
		if parsed, err := strconv.Atoi(v); err == nil {
			cfg.KafkaMessageTimeoutMs = parsed
		}
	}
	if v := os.Getenv("EUREKA_ENABLED"); v != "" {
		cfg.EurekaEnabled = v != "false"
	}
	if v := os.Getenv("EUREKA_SERVER_URL"); v != "" {
		cfg.EurekaServerURL = v
	}
	if v := os.Getenv("EUREKA_APP_NAME"); v != "" {
		cfg.EurekaAppName = v
	}
	if v := os.Getenv("APP_ADVERTISE_ADDRESS"); v != "" {
		cfg.EurekaInstanceHost = v
	}
	if v := os.Getenv("EUREKA_HEARTBEAT_INTERVAL_SECS"); v != "" {
		if parsed, err := strconv.Atoi(v); err == nil {
			cfg.EurekaHeartbeatIntervalSecs = parsed
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
		if explicitHost := os.Getenv("APP_ADVERTISE_ADDRESS"); explicitHost != "" {
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

// Individual database inputs replace the entire stored URI, including its credentials.
func mongoConfig(flatMap map[string]string) (string, string, error) {
	keys := []string{"DB_HOST", "DB_PORT", "DB_NAME", "DB_USERNAME", "DB_PASSWORD"}
	configured := false
	for _, key := range keys {
		if _, present := os.LookupEnv(key); present {
			configured = true
			break
		}
	}
	if !configured {
		uri, err := requireConfig(flatMap, "MONGODB_URI")
		if err != nil {
			return "", "", err
		}
		database, err := requireConfig(flatMap, "MONGODB_DB")
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
