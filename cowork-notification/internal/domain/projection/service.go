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
	UpsertChannelNotification(ctx context.Context, accountID, channelID int64, enabled bool, occurredAt time.Time) error
	UpsertUserProfile(ctx context.Context, userID int64, displayName string, occurredAt time.Time) error
	DeleteUserProfile(ctx context.Context, userID int64, occurredAt time.Time) error
	UpsertTeamProfile(ctx context.Context, teamID int64, teamName string, occurredAt time.Time) error
	DeleteTeamProfile(ctx context.Context, teamID int64, occurredAt time.Time) error
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
	EventType    string `json:"eventType"`
	AccountID    int64  `json:"accountId"`
	ChannelID    int64  `json:"channelId"`
	Notification *bool  `json:"notification"`
	OccurredAt   string `json:"occurredAt"`
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
	return s.store.UpsertChannelNotification(ctx, event.AccountID, event.ChannelID, *event.Notification, occurredAt)
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
