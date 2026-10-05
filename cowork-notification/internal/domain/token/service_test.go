package token_test

import (
	"context"
	"errors"
	"testing"

	"github.com/cowork/cowork-notification/internal/apperr"
	"github.com/cowork/cowork-notification/internal/domain/token"
	"github.com/cowork/cowork-notification/internal/infra/fcm"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

type mockRepo struct {
	tokens        map[int64][]token.DeviceToken
	err           error
	nextID        int64
	registerCalls int
}

func (m *mockRepo) Register(_ context.Context, requested *token.DeviceToken) (token.RegistrationResult, error) {
	m.registerCalls++
	if m.err != nil {
		return token.RegistrationResult{}, m.err
	}
	if m.tokens == nil {
		m.tokens = make(map[int64][]token.DeviceToken)
	}

	var previousAccountID int64
	for accountID, tokens := range m.tokens {
		for i, existing := range tokens {
			if existing.Token != requested.Token {
				continue
			}
			if accountID == requested.AccountID {
				existing.Platform = requested.Platform
				m.tokens[accountID][i] = existing
				requested.ID = existing.ID
				return token.RegistrationResult{DeviceTokenID: existing.ID}, nil
			}

			previousAccountID = accountID
			m.tokens[accountID] = append(tokens[:i], tokens[i+1:]...)
			break
		}
	}

	for _, tokens := range m.tokens {
		for _, existing := range tokens {
			if existing.ID > m.nextID {
				m.nextID = existing.ID
			}
		}
	}
	m.nextID++
	requested.ID = m.nextID
	m.tokens[requested.AccountID] = append(m.tokens[requested.AccountID], *requested)
	return token.RegistrationResult{
		DeviceTokenID:     requested.ID,
		PreviousAccountID: previousAccountID,
		Reassigned:        previousAccountID != 0,
	}, nil
}
func (m *mockRepo) FindByID(_ context.Context, _ int64) (*token.DeviceToken, error) {
	return nil, m.err
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
func (m *mockRepo) DeleteByAccountIDAndToken(_ context.Context, accountID int64, value string) error {
	if m.err != nil {
		return m.err
	}
	for i, existing := range m.tokens[accountID] {
		if existing.Token == value {
			m.tokens[accountID] = append(m.tokens[accountID][:i], m.tokens[accountID][i+1:]...)
			return nil
		}
	}
	return apperr.NotFound("token not found")
}

type mockFCM struct {
	calledTokens []string
	err          error
}

func (m *mockFCM) Send(_ context.Context, tokens []string, _, _ string, _ map[string]string) ([]fcm.TokenResult, error) {
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

func TestServiceRegisterTokenOwnership(t *testing.T) {
	t.Run("rejects an unsupported platform before persistence", func(t *testing.T) {
		repo := &mockRepo{}
		svc := token.NewService(repo, &mockFCM{}, &mockPref{}, nil)

		err := svc.RegisterToken(context.Background(), 1, "device-token", "DESKTOP")

		var appErr *apperr.AppError
		require.ErrorAs(t, err, &appErr)
		assert.Equal(t, 400, appErr.Code)
		assert.Zero(t, repo.registerCalls)
	})

	t.Run("refreshes the same owner without changing the token generation", func(t *testing.T) {
		repo := &mockRepo{tokens: map[int64][]token.DeviceToken{
			1: {{ID: 10, AccountID: 1, Token: "device-token", Platform: token.PlatformWeb}},
		}}
		svc := token.NewService(repo, &mockFCM{}, &mockPref{}, nil)

		err := svc.RegisterToken(context.Background(), 1, "device-token", "ANDROID")

		require.NoError(t, err)
		require.Len(t, repo.tokens[1], 1)
		assert.Equal(t, int64(10), repo.tokens[1][0].ID)
		assert.Equal(t, token.PlatformAndroid, repo.tokens[1][0].Platform)
	})

	t.Run("moves ownership to a new account and excludes the previous account", func(t *testing.T) {
		repo := &mockRepo{tokens: map[int64][]token.DeviceToken{
			1: {{ID: 10, AccountID: 1, Token: "device-token", Platform: token.PlatformIOS}},
		}}
		fcm := &mockFCM{}
		svc := token.NewService(repo, fcm, &mockPref{enabled: true}, nil)

		err := svc.RegisterToken(context.Background(), 2, "device-token", "IOS")
		require.NoError(t, err)
		assert.Empty(t, repo.tokens[1])
		require.Len(t, repo.tokens[2], 1)
		assert.NotEqual(t, int64(10), repo.tokens[2][0].ID)

		_, err = svc.Notify(context.Background(), "", []int64{1, 2}, nil, "title", "body", 0)
		require.NoError(t, err)
		assert.Equal(t, []string{"device-token"}, fcm.calledTokens)

		require.Error(t, svc.DeleteToken(context.Background(), 1, "device-token"))
		require.NoError(t, svc.DeleteToken(context.Background(), 2, "device-token"))
		assert.Empty(t, repo.tokens[2])
	})
}

func TestServiceNotifyAccordingToRecipientPreference(t *testing.T) {
	t.Run("enabled recipients receive notifications", func(t *testing.T) {
		repo := &mockRepo{tokens: map[int64][]token.DeviceToken{
			1: {{Token: "t1", AccountID: 1}},
			2: {{Token: "t2", AccountID: 2}},
		}}
		fcm := &mockFCM{}
		svc := token.NewService(repo, fcm, &mockPref{enabled: true}, nil)

		_, err := svc.Notify(context.Background(), "", []int64{1, 2}, nil, "title", "body", 0)

		require.NoError(t, err)
		assert.ElementsMatch(t, []string{"t1", "t2"}, fcm.calledTokens)
	})

	t.Run("muted recipients are excluded", func(t *testing.T) {
		repo := &mockRepo{tokens: map[int64][]token.DeviceToken{
			1: {{Token: "t1", AccountID: 1}},
		}}
		fcm := &mockFCM{}
		svc := token.NewService(repo, fcm, &mockPref{enabled: false}, nil)

		enabledIDs, err := svc.Notify(context.Background(), "", []int64{1}, nil, "title", "body", 42)

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
		svc := token.NewService(repo, fcm, &mockPref{enabled: false}, nil)

		enabledIDs, err := svc.Notify(context.Background(), "", []int64{1, 2}, []int64{2}, "title", "body", 42)

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
		svc := token.NewService(repo, fcm, &mockPref{enabled: true}, nil)

		enabledIDs, err := svc.Notify(context.Background(), "", []int64{1, 1, 2}, []int64{2, 2}, "title", "body", 42)

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
		svc := token.NewService(repo, fcm, pref, nil)

		_, err := svc.Notify(context.Background(), "", []int64{1}, nil, "title", "body", 42)

		require.Error(t, err)
		assert.Nil(t, fcm.calledTokens)
	})
}
