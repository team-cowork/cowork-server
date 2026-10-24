package projection

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"time"
)

var ErrInvalidEvent = errors.New("invalid projection event")
var ErrTopicGenerationMismatch = errors.New(
	"projection checkpoint belongs to a different Kafka topic generation; rebuild projection data and checkpoint together",
)

type Store interface {
	UpsertChannelNotification(
		ctx context.Context,
		accountID, channelID int64,
		level string,
		mutedUntil *time.Time,
		occurredAt time.Time,
	) error
	UpsertUserProfile(ctx context.Context, userID int64, displayName string, occurredAt time.Time) error
	DeleteUserProfile(ctx context.Context, userID int64, occurredAt time.Time) error
	UpsertTeamProfile(ctx context.Context, teamID int64, teamName string, occurredAt time.Time) error
	DeleteTeamProfile(ctx context.Context, teamID int64, occurredAt time.Time) error
	UpsertAccountNotificationSetting(ctx context.Context, setting AccountNotificationSetting, occurredAt time.Time) error
	UpsertTeamNotificationSetting(ctx context.Context, teamID int64, defaultLevel *string, occurredAt time.Time) error
}

type Checkpoint struct {
	ConsumerGroup string
	Topic         string
	Partition     int
	TopicID       string
	NextOffset    int64
}

type CheckpointState struct {
	TopicID                 string
	NextOffset              int64
	SnapshotCompletedOffset *int64
	InvalidRecordOffset     *int64
}

type SnapshotMarkerReceipt struct {
	Offset     int64
	SnapshotID string
	Source     string
	OccurredAt time.Time
}

type TopicPartition struct {
	Topic     string
	Partition int
}

type InvalidRecord struct {
	Checkpoint    Checkpoint
	Key           []byte
	Payload       []byte
	Reason        string
	LatchStateGap bool
}

type AtomicStore interface {
	Store
	ApplyWithCheckpoint(ctx context.Context, checkpoint Checkpoint, apply func(Store) error) error
	RecordSnapshotMarkerWithCheckpoint(
		ctx context.Context,
		checkpoint Checkpoint,
		marker SnapshotMarkerReceipt,
	) error
	QuarantineWithCheckpoint(ctx context.Context, record InvalidRecord) error
	LoadCheckpoint(ctx context.Context, group, topic string, partition int) (CheckpointState, bool, error)
	AdvanceCheckpoint(ctx context.Context, checkpoint Checkpoint) error
	LoadCheckpoints(
		ctx context.Context,
		group string,
		partitions []TopicPartition,
	) (map[TopicPartition]CheckpointState, error)
}

type ChannelNotificationEvent struct {
	EventType         string  `json:"eventType"`
	AccountID         int64   `json:"accountId"`
	ChannelID         int64   `json:"channelId"`
	Notification      *bool   `json:"notification"`
	NotificationLevel *string `json:"notificationLevel"`
	MutedUntil        *string `json:"mutedUntil"`
	OccurredAt        string  `json:"occurredAt"`
}

type UserProfileEvent struct {
	EventType  string `json:"eventType"`
	UserID     int64  `json:"userId"`
	Name       string `json:"name"`
	Nickname   string `json:"nickname"`
	OccurredAt string `json:"occurredAt"`
}

type TeamLifecycleEvent struct {
	EventType  string `json:"eventType"`
	TeamID     int64  `json:"teamId"`
	TeamName   string `json:"teamName"`
	OccurredAt string `json:"occurredAt"`
}

const settingStateSchemaVersion = 1

type DoNotDisturbScheduleEvent struct {
	Enabled *bool  `json:"enabled"`
	Start   string `json:"start"`
	End     string `json:"end"`
}

type AccountSettingEvent struct {
	EventType         string                     `json:"eventType"`
	SchemaVersion     int                        `json:"schemaVersion"`
	AccountID         int64                      `json:"accountId"`
	NotificationLevel *string                    `json:"notificationLevel"`
	Status            *string                    `json:"status"`
	StatusExpiresAt   *string                    `json:"statusExpiresAt"`
	Timezone          *string                    `json:"timezone"`
	DNDSchedule       *DoNotDisturbScheduleEvent `json:"dndSchedule"`
	OccurredAt        string                     `json:"occurredAt"`
}

type TeamSettingEvent struct {
	EventType                string  `json:"eventType"`
	SchemaVersion            int     `json:"schemaVersion"`
	TeamID                   int64   `json:"teamId"`
	DefaultNotificationLevel *string `json:"defaultNotificationLevel"`
	OccurredAt               string  `json:"occurredAt"`
}

// AccountNotificationSetting is the validated projection row of an account setting state.
// DND minutes are counted from midnight in Timezone. Timezone is stored as published because
// preference accepts Java zone IDs, such as offsets, that Go cannot load; readers fall back.
type AccountNotificationSetting struct {
	AccountID         int64
	NotificationLevel *string
	Status            *string
	StatusExpiresAt   *time.Time
	Timezone          *string
	DNDEnabled        bool
	DNDStartMinute    *int
	DNDEndMinute      *int
}

type Service struct {
	store Store
}

func NewService(store Store) *Service {
	return &Service{store: store}
}

func (s *Service) ApplyChannelNotificationWithCheckpoint(
	ctx context.Context,
	event ChannelNotificationEvent,
	checkpoint Checkpoint,
) error {
	store, err := s.atomicStore()
	if err != nil {
		return err
	}
	return store.ApplyWithCheckpoint(ctx, checkpoint, func(store Store) error {
		return NewService(store).ApplyChannelNotification(ctx, event)
	})
}

func (s *Service) ApplyUserProfileWithCheckpoint(
	ctx context.Context,
	event UserProfileEvent,
	checkpoint Checkpoint,
) error {
	store, err := s.atomicStore()
	if err != nil {
		return err
	}
	return store.ApplyWithCheckpoint(ctx, checkpoint, func(store Store) error {
		return NewService(store).ApplyUserProfile(ctx, event)
	})
}

func (s *Service) ApplyTeamLifecycleWithCheckpoint(
	ctx context.Context,
	event TeamLifecycleEvent,
	checkpoint Checkpoint,
) error {
	store, err := s.atomicStore()
	if err != nil {
		return err
	}
	return store.ApplyWithCheckpoint(ctx, checkpoint, func(store Store) error {
		return NewService(store).ApplyTeamLifecycle(ctx, event)
	})
}

func (s *Service) ApplyAccountSettingWithCheckpoint(
	ctx context.Context,
	event AccountSettingEvent,
	checkpoint Checkpoint,
) error {
	store, err := s.atomicStore()
	if err != nil {
		return err
	}
	return store.ApplyWithCheckpoint(ctx, checkpoint, func(store Store) error {
		return NewService(store).ApplyAccountSetting(ctx, event)
	})
}

func (s *Service) ApplyTeamSettingWithCheckpoint(
	ctx context.Context,
	event TeamSettingEvent,
	checkpoint Checkpoint,
) error {
	store, err := s.atomicStore()
	if err != nil {
		return err
	}
	return store.ApplyWithCheckpoint(ctx, checkpoint, func(store Store) error {
		return NewService(store).ApplyTeamSetting(ctx, event)
	})
}

func (s *Service) QuarantineWithCheckpoint(ctx context.Context, record InvalidRecord) error {
	store, err := s.atomicStore()
	if err != nil {
		return err
	}
	return store.QuarantineWithCheckpoint(ctx, record)
}

func (s *Service) RecordSnapshotMarkerWithCheckpoint(
	ctx context.Context,
	checkpoint Checkpoint,
	marker SnapshotMarkerReceipt,
) error {
	store, err := s.atomicStore()
	if err != nil {
		return err
	}
	return store.RecordSnapshotMarkerWithCheckpoint(ctx, checkpoint, marker)
}

func (s *Service) LoadCheckpoint(
	ctx context.Context,
	group, topic string,
	partition int,
) (CheckpointState, bool, error) {
	store, err := s.atomicStore()
	if err != nil {
		return CheckpointState{}, false, err
	}
	return store.LoadCheckpoint(ctx, group, topic, partition)
}

func (s *Service) AdvanceCheckpoint(ctx context.Context, checkpoint Checkpoint) error {
	store, err := s.atomicStore()
	if err != nil {
		return err
	}
	return store.AdvanceCheckpoint(ctx, checkpoint)
}

func (s *Service) LoadCheckpoints(
	ctx context.Context,
	group string,
	partitions []TopicPartition,
) (map[TopicPartition]CheckpointState, error) {
	store, err := s.atomicStore()
	if err != nil {
		return nil, err
	}
	return store.LoadCheckpoints(ctx, group, partitions)
}

func (s *Service) atomicStore() (AtomicStore, error) {
	store, ok := s.store.(AtomicStore)
	if !ok {
		return nil, errors.New("projection service requires an AtomicStore for checkpointed operations")
	}
	return store, nil
}

func (s *Service) ApplyChannelNotification(ctx context.Context, event ChannelNotificationEvent) error {
	if event.EventType != "UPSERT" || event.AccountID <= 0 || event.ChannelID <= 0 || event.Notification == nil {
		return invalidEvent("channel notification", event.EventType)
	}
	occurredAt, err := parseOccurredAt(event.OccurredAt)
	if err != nil {
		return invalidEvent("channel notification occurredAt", event.EventType)
	}
	level, ok := channelNotificationLevel(event)
	if !ok {
		return invalidEvent("channel notification notificationLevel", event.EventType)
	}
	var mutedUntil *time.Time
	if event.MutedUntil != nil {
		parsed, err := time.Parse(time.RFC3339Nano, *event.MutedUntil)
		if err != nil {
			return invalidEvent("channel notification mutedUntil", event.EventType)
		}
		parsed = parsed.UTC()
		mutedUntil = &parsed
	}
	return s.store.UpsertChannelNotification(ctx, event.AccountID, event.ChannelID, level, mutedUntil, occurredAt)
}

func (s *Service) ApplyAccountSetting(ctx context.Context, event AccountSettingEvent) error {
	if event.EventType != "UPSERT" || event.SchemaVersion != settingStateSchemaVersion || event.AccountID <= 0 {
		return invalidEvent("account setting", event.EventType)
	}
	occurredAt, err := parseOccurredAt(event.OccurredAt)
	if err != nil {
		return invalidEvent("account setting occurredAt", event.EventType)
	}
	if event.NotificationLevel != nil && !isNotificationLevel(*event.NotificationLevel) {
		return invalidEvent("account setting notificationLevel", event.EventType)
	}
	if event.Status != nil && *event.Status != "ONLINE" && *event.Status != "DO_NOT_DISTURB" {
		return invalidEvent("account setting status", event.EventType)
	}
	setting := AccountNotificationSetting{
		AccountID:         event.AccountID,
		NotificationLevel: event.NotificationLevel,
		Status:            event.Status,
		Timezone:          event.Timezone,
	}
	if event.StatusExpiresAt != nil {
		expiresAt, err := time.Parse(time.RFC3339Nano, *event.StatusExpiresAt)
		if err != nil {
			return invalidEvent("account setting statusExpiresAt", event.EventType)
		}
		expiresAt = expiresAt.UTC()
		setting.StatusExpiresAt = &expiresAt
	}
	if schedule := event.DNDSchedule; schedule != nil {
		start, startErr := minuteOfDay(schedule.Start)
		end, endErr := minuteOfDay(schedule.End)
		if schedule.Enabled == nil || startErr != nil || endErr != nil {
			return invalidEvent("account setting dndSchedule", event.EventType)
		}
		setting.DNDEnabled = *schedule.Enabled
		setting.DNDStartMinute = &start
		setting.DNDEndMinute = &end
	}
	return s.store.UpsertAccountNotificationSetting(ctx, setting, occurredAt)
}

func (s *Service) ApplyTeamSetting(ctx context.Context, event TeamSettingEvent) error {
	if event.EventType != "UPSERT" || event.SchemaVersion != settingStateSchemaVersion || event.TeamID <= 0 {
		return invalidEvent("team setting", event.EventType)
	}
	occurredAt, err := parseOccurredAt(event.OccurredAt)
	if err != nil {
		return invalidEvent("team setting occurredAt", event.EventType)
	}
	if event.DefaultNotificationLevel != nil && !isNotificationLevel(*event.DefaultNotificationLevel) {
		return invalidEvent("team setting defaultNotificationLevel", event.EventType)
	}
	return s.store.UpsertTeamNotificationSetting(ctx, event.TeamID, event.DefaultNotificationLevel, occurredAt)
}

func isNotificationLevel(level string) bool {
	return level == "ALL" || level == "MENTIONS" || level == "NONE"
}

func minuteOfDay(value string) (int, error) {
	parsed, err := time.Parse("15:04", value)
	if err != nil {
		return 0, err
	}
	return parsed.Hour()*60 + parsed.Minute(), nil
}

// channelNotificationLevel falls back to the boolean notification flag for records
// published before notificationLevel was added.
func channelNotificationLevel(event ChannelNotificationEvent) (string, bool) {
	if event.NotificationLevel == nil {
		if *event.Notification {
			return "ALL", true
		}
		return "NONE", true
	}
	level := *event.NotificationLevel
	return level, isNotificationLevel(level)
}

func (s *Service) ApplyUserProfile(ctx context.Context, event UserProfileEvent) error {
	if event.UserID <= 0 {
		return invalidEvent("user profile", event.EventType)
	}
	occurredAt, err := parseOccurredAt(event.OccurredAt)
	if err != nil {
		return invalidEvent("user profile occurredAt", event.EventType)
	}

	switch event.EventType {
	case "UPSERT":
		displayName := strings.TrimSpace(event.Nickname)
		if displayName == "" {
			displayName = strings.TrimSpace(event.Name)
		}
		if displayName == "" {
			return invalidEvent("user profile has no display name", event.EventType)
		}
		return s.store.UpsertUserProfile(ctx, event.UserID, displayName, occurredAt)
	case "DELETE":
		return s.store.DeleteUserProfile(ctx, event.UserID, occurredAt)
	default:
		return invalidEvent("user profile", event.EventType)
	}
}

func (s *Service) ApplyTeamLifecycle(ctx context.Context, event TeamLifecycleEvent) error {
	if event.TeamID <= 0 {
		return invalidEvent("team lifecycle", event.EventType)
	}
	occurredAt, err := parseOccurredAt(event.OccurredAt)
	if err != nil {
		return invalidEvent("team lifecycle occurredAt", event.EventType)
	}
	switch event.EventType {
	case "TEAM_DELETED":
		return s.store.DeleteTeamProfile(ctx, event.TeamID, occurredAt)
	case "TEAM_CREATED", "TEAM_UPDATED", "MEMBER_INVITED", "MEMBER_JOINED", "MEMBER_REMOVED", "ROLE_CHANGED":
		// Every non-delete lifecycle event carries the current team name and can
		// refresh the local display projection, including periodic snapshots.
	default:
		return invalidEvent("team lifecycle", event.EventType)
	}
	teamName := strings.TrimSpace(event.TeamName)
	if teamName == "" {
		return invalidEvent("team lifecycle", event.EventType)
	}
	return s.store.UpsertTeamProfile(ctx, event.TeamID, teamName, occurredAt)
}

func invalidEvent(kind, eventType string) error {
	return fmt.Errorf("%w: %s eventType=%q", ErrInvalidEvent, kind, eventType)
}

func parseOccurredAt(value string) (time.Time, error) {
	parsed, err := time.Parse(time.RFC3339Nano, value)
	if err != nil {
		return time.Time{}, fmt.Errorf("invalid occurredAt %q: %w", value, err)
	}
	return parsed.UTC(), nil
}
