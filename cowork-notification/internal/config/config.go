package config

import (
	"fmt"
	"os"
	"strconv"
)

type AppConfig struct {
	KafkaSecurity                 KafkaSecurity
	Port                          string
	DBPath                        string
	DBDSN                         string
	KafkaBrokers                  string
	KafkaTopicNotify              string
	KafkaGroupID                  string
	KafkaProjectionGroupID        string
	KafkaTopicChannelNotification string
	KafkaTopicUserProfile         string
	KafkaTopicTeamLifecycle       string
	KafkaTopicAccountSetting      string
	KafkaTopicTeamSetting         string
	FCMCredentialsJSON            string
	FCMDataOnly                   bool
}

func Load() (*AppConfig, error) {
	security, err := loadKafkaSecurity()
	if err != nil {
		return nil, err
	}
	cfg := &AppConfig{
		KafkaSecurity:                 security,
		Port:                          getEnv("PORT", "8086"),
		DBPath:                        os.Getenv("DB_PATH"),
		KafkaBrokers:                  getEnv("KAFKA_BOOTSTRAP_SERVERS", "localhost:9094"),
		KafkaTopicNotify:              getEnv("KAFKA_TOPIC_NOTIFICATION", "notification.trigger"),
		KafkaGroupID:                  getEnv("KAFKA_GROUP_ID", "cowork-notification"),
		KafkaProjectionGroupID:        getEnv("KAFKA_PROJECTION_GROUP_ID", "cowork-notification-projections"),
		KafkaTopicChannelNotification: getEnv("KAFKA_TOPIC_CHANNEL_NOTIFICATION_PREFERENCE", "preference.channel-notification.changed"),
		KafkaTopicUserProfile:         getEnv("KAFKA_TOPIC_USER_PROFILE", "user.profile.event.v2"),
		KafkaTopicTeamLifecycle:       getEnv("KAFKA_TOPIC_TEAM_LIFECYCLE", "team.lifecycle"),
		KafkaTopicAccountSetting:      getEnv("KAFKA_TOPIC_ACCOUNT_SETTING", "preference.account-setting.state.v1"),
		KafkaTopicTeamSetting:         getEnv("KAFKA_TOPIC_TEAM_SETTING", "preference.team-setting.state.v1"),
		FCMCredentialsJSON:            os.Getenv("FCM_CREDENTIALS_JSON"),
	}
	if path := os.Getenv("FCM_CREDENTIALS_FILE"); path != "" {
		data, err := os.ReadFile(path)
		if err != nil {
			return nil, fmt.Errorf("read FCM_CREDENTIALS_FILE: %w", err)
		}
		cfg.FCMCredentialsJSON = string(data)
	}
	cfg.DBDSN = sqliteDSN(cfg.DBPath)
	cfg.FCMDataOnly, err = strconv.ParseBool(getEnv("FCM_DATA_ONLY", "false"))
	if err != nil {
		return nil, fmt.Errorf("invalid FCM_DATA_ONLY: %w", err)
	}
	return validate(cfg)
}

func getEnv(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

func validate(cfg *AppConfig) (*AppConfig, error) {
	required := map[string]string{
		"DB_PATH":                   cfg.DBPath,
		"KAFKA_BOOTSTRAP_SERVERS":   cfg.KafkaBrokers,
		"KAFKA_TOPIC_NOTIFICATION":  cfg.KafkaTopicNotify,
		"KAFKA_GROUP_ID":            cfg.KafkaGroupID,
		"KAFKA_PROJECTION_GROUP_ID": cfg.KafkaProjectionGroupID,
		"FCM_CREDENTIALS_JSON":      cfg.FCMCredentialsJSON,
	}
	for name, val := range required {
		if val == "" {
			return nil, fmt.Errorf("required config %q is not set", name)
		}
	}

	return cfg, nil
}

// Transactions take the write lock at BEGIN, so a read-then-write transaction
// never fails on a lock upgrade.
func sqliteDSN(path string) string {
	if path == "" {
		return ""
	}
	return "file:" + path + "?_journal_mode=WAL&_synchronous=NORMAL&_busy_timeout=5000&_txlock=immediate"
}
