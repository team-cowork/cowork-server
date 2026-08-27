package projection_test

import (
	"context"
	"testing"
	"time"

	"github.com/cowork/cowork-notification/internal/domain/projection"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

type fakeStore struct {
	preferenceAccountID int64
	preferenceChannelID int64
	preferenceEnabled   bool
	userID              int64
	displayName         string
	deletedUserID       int64
	teamID              int64
	teamName            string
	deletedTeamID       int64
}

type fakeAtomicStore struct {
	fakeStore
	checkpoint           projection.Checkpoint
	marker               projection.SnapshotMarkerReceipt
	checkpointAfterApply bool
}

func (f *fakeAtomicStore) ApplyWithCheckpoint(
	ctx context.Context,
	checkpoint projection.Checkpoint,
	apply func(projection.Store) error,
) error {
	if err := apply(&f.fakeStore); err != nil {
		return err
	}
	f.checkpointAfterApply = f.userID != 0
	f.checkpoint = checkpoint
	return nil
}

func (*fakeAtomicStore) QuarantineWithCheckpoint(context.Context, projection.InvalidRecord) error {
	return nil
}

func (f *fakeAtomicStore) RecordSnapshotMarkerWithCheckpoint(
	_ context.Context,
	checkpoint projection.Checkpoint,
	marker projection.SnapshotMarkerReceipt,
) error {
	f.checkpoint = checkpoint
	f.marker = marker
	return nil
}

func (*fakeAtomicStore) LoadCheckpoint(
	context.Context,
	string,
	string,
	int,
) (projection.CheckpointState, bool, error) {
	return projection.CheckpointState{}, false, nil
}

func (*fakeAtomicStore) AdvanceCheckpoint(context.Context, projection.Checkpoint) error {
	return nil
}

func (*fakeAtomicStore) LoadCheckpoints(
	context.Context,
	string,
	[]projection.TopicPartition,
) (map[projection.TopicPartition]projection.CheckpointState, error) {
	return map[projection.TopicPartition]projection.CheckpointState{}, nil
}

func (f *fakeStore) UpsertChannelNotification(_ context.Context, accountID, channelID int64, enabled bool, _ time.Time) error {
	f.preferenceAccountID = accountID
	f.preferenceChannelID = channelID
	f.preferenceEnabled = enabled
	return nil
}

func (f *fakeStore) UpsertUserProfile(_ context.Context, userID int64, displayName string, _ time.Time) error {
	f.userID = userID
	f.displayName = displayName
	return nil
}

func (f *fakeStore) DeleteUserProfile(_ context.Context, userID int64, _ time.Time) error {
	f.deletedUserID = userID
	return nil
}

func (f *fakeStore) UpsertTeamProfile(_ context.Context, teamID int64, teamName string, _ time.Time) error {
	f.teamID = teamID
	f.teamName = teamName
	return nil
}

func (f *fakeStore) DeleteTeamProfile(_ context.Context, teamID int64, _ time.Time) error {
	f.deletedTeamID = teamID
	return nil
}

func TestService_ApplyChannelNotification(t *testing.T) {
	store := &fakeStore{}
	svc := projection.NewService(store)
	disabled := false

	err := svc.ApplyChannelNotification(context.Background(), projection.ChannelNotificationEvent{
		EventType:    "UPSERT",
		AccountID:    11,
		ChannelID:    22,
		Notification: &disabled,
		OccurredAt:   "2026-08-26T01:02:03Z",
	})

	require.NoError(t, err)
	assert.Equal(t, int64(11), store.preferenceAccountID)
	assert.Equal(t, int64(22), store.preferenceChannelID)
	assert.False(t, store.preferenceEnabled)
}

func TestService_ApplyUserProfile_prefersNonBlankNickname(t *testing.T) {
	store := &fakeStore{}
	svc := projection.NewService(store)

	err := svc.ApplyUserProfile(context.Background(), projection.UserProfileEvent{
		EventType:  "UPSERT",
		UserID:     33,
		Name:       "홍길동",
		Nickname:   " 길동 ",
		OccurredAt: "2026-08-26T01:02:03Z",
	})

	require.NoError(t, err)
	assert.Equal(t, int64(33), store.userID)
	assert.Equal(t, "길동", store.displayName)
}

func TestService_ApplyUserProfile_fallsBackToNameAndHandlesDelete(t *testing.T) {
	store := &fakeStore{}
	svc := projection.NewService(store)

	require.NoError(t, svc.ApplyUserProfile(context.Background(), projection.UserProfileEvent{
		EventType:  "UPSERT",
		UserID:     44,
		Name:       "김코워크",
		Nickname:   "   ",
		OccurredAt: "2026-08-26T01:02:03Z",
	}))
	assert.Equal(t, "김코워크", store.displayName)

	require.NoError(t, svc.ApplyUserProfile(context.Background(), projection.UserProfileEvent{
		EventType:  "DELETE",
		UserID:     44,
		OccurredAt: "2026-08-26T01:02:04Z",
	}))
	assert.Equal(t, int64(44), store.deletedUserID)
}

func TestService_ApplyTeamLifecycle_updatesNameAndDeletesTeam(t *testing.T) {
	store := &fakeStore{}
	svc := projection.NewService(store)

	require.NoError(t, svc.ApplyTeamLifecycle(context.Background(), projection.TeamLifecycleEvent{
		EventType:  "MEMBER_JOINED",
		TeamID:     55,
		TeamName:   "코워크 팀",
		OccurredAt: "2026-08-26T10:02:03Z",
	}))
	assert.Equal(t, "코워크 팀", store.teamName)

	require.NoError(t, svc.ApplyTeamLifecycle(context.Background(), projection.TeamLifecycleEvent{
		EventType:  "TEAM_DELETED",
		TeamID:     55,
		OccurredAt: "2026-08-26T10:02:04Z",
	}))
	assert.Equal(t, int64(55), store.deletedTeamID)
}

func TestService_rejectsOffsetLessOccurredAt(t *testing.T) {
	store := &fakeStore{}
	svc := projection.NewService(store)

	err := svc.ApplyTeamLifecycle(context.Background(), projection.TeamLifecycleEvent{
		EventType:  "MEMBER_JOINED",
		TeamID:     55,
		TeamName:   "코워크 팀",
		OccurredAt: "2026-08-26T10:02:03",
	})

	assert.ErrorIs(t, err, projection.ErrInvalidEvent)
}

func TestService_checkpointedApplyRunsProjectionBeforeCheckpoint(t *testing.T) {
	store := &fakeAtomicStore{}
	svc := projection.NewService(store)
	checkpoint := projection.Checkpoint{
		ConsumerGroup: "cowork-notification-projections",
		Topic:         "user.profile.event",
		Partition:     1,
		TopicID:       "93b19168-4a63-49cd-b01d-b8d0667a1cb5",
		NextOffset:    12,
	}

	err := svc.ApplyUserProfileWithCheckpoint(context.Background(), projection.UserProfileEvent{
		EventType:  "UPSERT",
		UserID:     44,
		Nickname:   "길동",
		OccurredAt: "2026-08-26T10:02:03Z",
	}, checkpoint)

	require.NoError(t, err)
	assert.True(t, store.checkpointAfterApply)
	assert.Equal(t, checkpoint, store.checkpoint)
}

func TestService_recordsSnapshotMarkerAndCheckpointTogether(t *testing.T) {
	store := &fakeAtomicStore{}
	svc := projection.NewService(store)
	checkpoint := projection.Checkpoint{
		ConsumerGroup: "cowork-notification-projections",
		Topic:         "user.profile.event",
		Partition:     1,
		TopicID:       "93b19168-4a63-49cd-b01d-b8d0667a1cb5",
		NextOffset:    13,
	}
	marker := projection.SnapshotMarkerReceipt{
		Offset:     12,
		SnapshotID: "93b19168-4a63-49cd-b01d-b8d0667a1cb5",
		Source:     "cowork-user",
		OccurredAt: time.Date(2026, 8, 26, 10, 2, 3, 0, time.UTC),
	}

	err := svc.RecordSnapshotMarkerWithCheckpoint(context.Background(), checkpoint, marker)

	require.NoError(t, err)
	assert.Equal(t, checkpoint, store.checkpoint)
	assert.Equal(t, marker, store.marker)
}

func TestService_rejectsInvalidProjectionEvent(t *testing.T) {
	store := &fakeStore{}
	svc := projection.NewService(store)
	enabled := true

	err := svc.ApplyChannelNotification(context.Background(), projection.ChannelNotificationEvent{
		EventType:    "UPSERT",
		AccountID:    0,
		ChannelID:    22,
		Notification: &enabled,
		OccurredAt:   "2026-08-26T01:02:03Z",
	})

	assert.ErrorIs(t, err, projection.ErrInvalidEvent)
}

func TestService_rejectsUnknownTeamLifecycleContract(t *testing.T) {
	store := &fakeStore{}
	svc := projection.NewService(store)

	err := svc.ApplyTeamLifecycle(context.Background(), projection.TeamLifecycleEvent{
		EventType:  "UNKNOWN",
		TeamID:     55,
		TeamName:   "코워크 팀",
		OccurredAt: "2026-08-26T01:02:03Z",
	})

	assert.ErrorIs(t, err, projection.ErrInvalidEvent)
	assert.Zero(t, store.teamID)
}
