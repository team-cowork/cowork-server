package token_test

import (
	"context"
	"errors"
	"testing"

	"github.com/cowork/cowork-notification/internal/domain/token"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

type mockRepo struct {
	tokens map[int64][]token.DeviceToken
	err    error
}

func (m *mockRepo) Save(_ context.Context, _ *token.DeviceToken) error {
	return m.err
}
func (m *mockRepo) FindByAccountID(_ context.Context, id int64) ([]token.DeviceToken, error) {
	return m.tokens[id], m.err
}
func (m *mockRepo) FindByAccountIDs(_ context.Context, ids []int64) (map[int64][]token.DeviceToken, error) {
	result := make(map[int64][]token.DeviceToken)
	for _, id := range ids {
		if ts, ok := m.tokens[id]; ok {
			result[id] = ts
		}
	}
	return result, m.err
}
func (m *mockRepo) DeleteByTokens(_ context.Context, _ []string) error {
	return m.err
}
func (m *mockRepo) DeleteByAccountIDAndToken(_ context.Context, _ int64, _ string) error {
	return m.err
}

type mockFCM struct {
	calledTokens []string
	err          error
}

func (m *mockFCM) Send(_ context.Context, tokens []string, _, _ string, _ map[string]string) ([]string, error) {
	m.calledTokens = tokens
	return nil, m.err
}

type mockPref struct {
	enabled bool
	err     error
}

func (m *mockPref) AreNotificationsEnabled(_ context.Context, accountIDs []int64, _ int64) (map[int64]bool, error) {
	if m.err != nil {
		return nil, m.err
	}
	result := make(map[int64]bool, len(accountIDs))
	for _, id := range accountIDs {
		result[id] = m.enabled
	}
	return result, nil
}

func TestServiceNotifyAccordingToRecipientPreference(t *testing.T) {
	t.Run("enabled recipients receive notifications", func(t *testing.T) {
		repo := &mockRepo{tokens: map[int64][]token.DeviceToken{
			1: {{Token: "t1", AccountID: 1}},
			2: {{Token: "t2", AccountID: 2}},
		}}
		fcm := &mockFCM{}
		svc := token.NewService(repo, fcm, &mockPref{enabled: true})

		_, err := svc.Notify(context.Background(), []int64{1, 2}, nil, "title", "body", 0)

		require.NoError(t, err)
		assert.ElementsMatch(t, []string{"t1", "t2"}, fcm.calledTokens)
	})

	t.Run("muted recipients are excluded", func(t *testing.T) {
		repo := &mockRepo{tokens: map[int64][]token.DeviceToken{
			1: {{Token: "t1", AccountID: 1}},
		}}
		fcm := &mockFCM{}
		svc := token.NewService(repo, fcm, &mockPref{enabled: false})

		enabledIDs, err := svc.Notify(context.Background(), []int64{1}, nil, "title", "body", 42)

		require.NoError(t, err)
		assert.Nil(t, fcm.calledTokens)
		assert.Empty(t, enabledIDs)
	})

	t.Run("forced recipients bypass mute", func(t *testing.T) {
		repo := &mockRepo{tokens: map[int64][]token.DeviceToken{
			1: {{Token: "t1", AccountID: 1}},
			2: {{Token: "t2", AccountID: 2}},
		}}
		fcm := &mockFCM{}
		svc := token.NewService(repo, fcm, &mockPref{enabled: false})

		enabledIDs, err := svc.Notify(context.Background(), []int64{1, 2}, []int64{2}, "title", "body", 42)

		require.NoError(t, err)
		assert.Equal(t, []string{"t2"}, fcm.calledTokens)
		assert.Equal(t, []int64{2}, enabledIDs)
	})

	t.Run("duplicate target and forced recipients receive one notification", func(t *testing.T) {
		repo := &mockRepo{tokens: map[int64][]token.DeviceToken{
			1: {{Token: "t1", AccountID: 1}},
			2: {{Token: "t2", AccountID: 2}},
		}}
		fcm := &mockFCM{}
		svc := token.NewService(repo, fcm, &mockPref{enabled: true})

		enabledIDs, err := svc.Notify(context.Background(), []int64{1, 1, 2}, []int64{2, 2}, "title", "body", 42)

		require.NoError(t, err)
		assert.ElementsMatch(t, []string{"t1", "t2"}, fcm.calledTokens)
		assert.Equal(t, []int64{2, 1}, enabledIDs)
	})

	t.Run("preference lookup failure fails closed without sending", func(t *testing.T) {
		repo := &mockRepo{tokens: map[int64][]token.DeviceToken{
			1: {{Token: "t1", AccountID: 1}},
		}}
		fcm := &mockFCM{}
		pref := &mockPref{err: errors.New("preference service unreachable")}
		svc := token.NewService(repo, fcm, pref)

		_, err := svc.Notify(context.Background(), []int64{1}, nil, "title", "body", 42)

		require.Error(t, err)
		assert.Nil(t, fcm.calledTokens)
	})
}
