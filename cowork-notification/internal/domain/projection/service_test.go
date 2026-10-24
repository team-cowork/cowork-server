package projection_test

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/cowork/cowork-notification/internal/domain/projection"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

type channelNotificationWrite struct {
	level      string
	mutedUntil *time.Time
}

type recordingStore struct {
	channelNotifications []channelNotificationWrite
	accountSettings      []projection.AccountNotificationSetting
	teamDefaultLevels    []*string
}

func (s *recordingStore) UpsertAccountNotificationSetting(
	_ context.Context,
	setting projection.AccountNotificationSetting,
	_ time.Time,
) error {
	s.accountSettings = append(s.accountSettings, setting)
	return nil
}

func (s *recordingStore) UpsertTeamNotificationSetting(_ context.Context, _ int64, level *string, _ time.Time) error {
	s.teamDefaultLevels = append(s.teamDefaultLevels, level)
	return nil
}

func (s *recordingStore) UpsertChannelNotification(
	_ context.Context,
	_, _ int64,
	level string,
	mutedUntil *time.Time,
	_ time.Time,
) error {
	s.channelNotifications = append(s.channelNotifications, channelNotificationWrite{level, mutedUntil})
	return nil
}

func (*recordingStore) UpsertUserProfile(context.Context, int64, string, time.Time) error { return nil }
func (*recordingStore) DeleteUserProfile(context.Context, int64, time.Time) error         { return nil }
func (*recordingStore) UpsertTeamProfile(context.Context, int64, string, time.Time) error { return nil }
func (*recordingStore) DeleteTeamProfile(context.Context, int64, time.Time) error         { return nil }

func channelNotificationEvent() projection.ChannelNotificationEvent {
	enabled := true
	return projection.ChannelNotificationEvent{
		EventType:    "UPSERT",
		AccountID:    1,
		ChannelID:    2,
		Notification: &enabled,
		OccurredAt:   "2026-10-23T00:00:00Z",
	}
}

func stringPointer(value string) *string { return &value }

func TestApplyChannelNotification(t *testing.T) {
	t.Run("stores notification level and muted until", func(t *testing.T) {
		store := &recordingStore{}
		event := channelNotificationEvent()
		event.NotificationLevel = stringPointer("MENTIONS")
		event.MutedUntil = stringPointer("2026-10-24T09:00:00+09:00")

		err := projection.NewService(store).ApplyChannelNotification(context.Background(), event)

		require.NoError(t, err)
		require.Len(t, store.channelNotifications, 1)
		assert.Equal(t, "MENTIONS", store.channelNotifications[0].level)
		assert.Equal(t, time.Date(2026, 10, 24, 0, 0, 0, 0, time.UTC), *store.channelNotifications[0].mutedUntil)
	})

	t.Run("derives level from notification flag for records without a level", func(t *testing.T) {
		store := &recordingStore{}
		disabled := channelNotificationEvent()
		off := false
		disabled.Notification = &off

		require.NoError(t, projection.NewService(store).ApplyChannelNotification(context.Background(), channelNotificationEvent()))
		require.NoError(t, projection.NewService(store).ApplyChannelNotification(context.Background(), disabled))

		require.Len(t, store.channelNotifications, 2)
		assert.Equal(t, "ALL", store.channelNotifications[0].level)
		assert.Equal(t, "NONE", store.channelNotifications[1].level)
		assert.Nil(t, store.channelNotifications[0].mutedUntil)
	})

	t.Run("rejects unknown level and malformed muted until", func(t *testing.T) {
		unknownLevel := channelNotificationEvent()
		unknownLevel.NotificationLevel = stringPointer("SOMETIMES")
		badMutedUntil := channelNotificationEvent()
		badMutedUntil.MutedUntil = stringPointer("tomorrow")

		for _, event := range []projection.ChannelNotificationEvent{unknownLevel, badMutedUntil} {
			store := &recordingStore{}
			err := projection.NewService(store).ApplyChannelNotification(context.Background(), event)

			assert.True(t, errors.Is(err, projection.ErrInvalidEvent))
			assert.Empty(t, store.channelNotifications)
		}
	})
}

func accountSettingEvent() projection.AccountSettingEvent {
	return projection.AccountSettingEvent{
		EventType:     "UPSERT",
		SchemaVersion: 1,
		AccountID:     7,
		OccurredAt:    "2026-10-23T00:00:00Z",
	}
}

func TestApplyAccountSetting(t *testing.T) {
	t.Run("stores level, status, timezone and DND minutes", func(t *testing.T) {
		store := &recordingStore{}
		enabled := true
		event := accountSettingEvent()
		event.NotificationLevel = stringPointer("MENTIONS")
		event.Status = stringPointer("DO_NOT_DISTURB")
		event.StatusExpiresAt = stringPointer("2026-10-23T18:00:00+09:00")
		event.Timezone = stringPointer("Asia/Seoul")
		event.DNDSchedule = &projection.DoNotDisturbScheduleEvent{Enabled: &enabled, Start: "22:30", End: "07:00"}

		err := projection.NewService(store).ApplyAccountSetting(context.Background(), event)

		require.NoError(t, err)
		require.Len(t, store.accountSettings, 1)
		stored := store.accountSettings[0]
		assert.Equal(t, int64(7), stored.AccountID)
		assert.Equal(t, "MENTIONS", *stored.NotificationLevel)
		assert.Equal(t, "DO_NOT_DISTURB", *stored.Status)
		assert.Equal(t, time.Date(2026, 10, 23, 9, 0, 0, 0, time.UTC), *stored.StatusExpiresAt)
		assert.True(t, stored.DNDEnabled)
		assert.Equal(t, 22*60+30, *stored.DNDStartMinute)
		assert.Equal(t, 7*60, *stored.DNDEndMinute)
	})

	t.Run("stores a timezone Go cannot load instead of quarantining the record", func(t *testing.T) {
		store := &recordingStore{}
		event := accountSettingEvent()
		event.Timezone = stringPointer("+09:00")

		require.NoError(t, projection.NewService(store).ApplyAccountSetting(context.Background(), event))

		require.Len(t, store.accountSettings, 1)
		assert.Equal(t, "+09:00", *store.accountSettings[0].Timezone)
	})

	t.Run("stores an empty setting when no notification value is set", func(t *testing.T) {
		store := &recordingStore{}

		require.NoError(t, projection.NewService(store).ApplyAccountSetting(context.Background(), accountSettingEvent()))

		require.Len(t, store.accountSettings, 1)
		assert.Nil(t, store.accountSettings[0].NotificationLevel)
		assert.False(t, store.accountSettings[0].DNDEnabled)
	})

	t.Run("rejects invalid values", func(t *testing.T) {
		enabled := true
		mutations := []func(*projection.AccountSettingEvent){
			func(e *projection.AccountSettingEvent) { e.SchemaVersion = 2 },
			func(e *projection.AccountSettingEvent) { e.NotificationLevel = stringPointer("LOUD") },
			func(e *projection.AccountSettingEvent) { e.Status = stringPointer("BUSY") },
			func(e *projection.AccountSettingEvent) { e.StatusExpiresAt = stringPointer("later") },
			func(e *projection.AccountSettingEvent) {
				e.DNDSchedule = &projection.DoNotDisturbScheduleEvent{Enabled: &enabled, Start: "25:00", End: "07:00"}
			},
			func(e *projection.AccountSettingEvent) {
				e.DNDSchedule = &projection.DoNotDisturbScheduleEvent{Start: "22:00", End: "07:00"}
			},
		}
		for _, mutate := range mutations {
			store := &recordingStore{}
			event := accountSettingEvent()
			mutate(&event)

			err := projection.NewService(store).ApplyAccountSetting(context.Background(), event)

			assert.True(t, errors.Is(err, projection.ErrInvalidEvent))
			assert.Empty(t, store.accountSettings)
		}
	})
}

func TestApplyTeamSetting(t *testing.T) {
	event := projection.TeamSettingEvent{
		EventType:                "UPSERT",
		SchemaVersion:            1,
		TeamID:                   3,
		DefaultNotificationLevel: stringPointer("NONE"),
		OccurredAt:               "2026-10-23T00:00:00Z",
	}
	store := &recordingStore{}

	require.NoError(t, projection.NewService(store).ApplyTeamSetting(context.Background(), event))
	require.Len(t, store.teamDefaultLevels, 1)
	assert.Equal(t, "NONE", *store.teamDefaultLevels[0])

	event.DefaultNotificationLevel = stringPointer("LOUD")
	err := projection.NewService(&recordingStore{}).ApplyTeamSetting(context.Background(), event)
	assert.True(t, errors.Is(err, projection.ErrInvalidEvent))
}
