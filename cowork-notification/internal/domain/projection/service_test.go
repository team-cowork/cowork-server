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
