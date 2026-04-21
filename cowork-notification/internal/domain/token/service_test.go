package token_test

import (
	"context"
	"errors"
	"testing"

	"github.com/cowork/cowork-notification/internal/domain/token"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// --- mocks ---

type mockRepo struct {
	saved   *token.DeviceToken
	tokens  map[int64][]token.DeviceToken
	deleted string
	err     error
}

func (m *mockRepo) Save(_ context.Context, t *token.DeviceToken) error {
	m.saved = t
	return m.err
}
func (m *mockRepo) FindByAccountID(_ context.Context, id int64) ([]token.DeviceToken, error) {
	return m.tokens[id], m.err
}
func (m *mockRepo) DeleteByToken(_ context.Context, tkn string) error {
	m.deleted = tkn
	return m.err
}

func (m *mockRepo) DeleteByAccountIDAndToken(_ context.Context, _ int64, tkn string) error {
	m.deleted = tkn
	return m.err
}

type mockFCM struct {
	calledTokens []string
	invalid      []string
	err          error
}

func (m *mockFCM) Send(_ context.Context, tokens []string, _, _ string, _ map[string]string) ([]string, error) {
	m.calledTokens = tokens
	return m.invalid, m.err
}

type mockPref struct {
	enabled bool
	err     error
}

func (m *mockPref) IsNotificationEnabled(_ context.Context, _, _ int64) (bool, error) {
	return m.enabled, m.err
}

// --- tests ---

func TestService_RegisterToken(t *testing.T) {
	repo := &mockRepo{}
	svc := token.NewService(repo, &mockFCM{}, &mockPref{enabled: true})

	err := svc.RegisterToken(context.Background(), 1, "token-abc", "ANDROID")
	require.NoError(t, err)
	assert.Equal(t, int64(1), repo.saved.AccountID)
	assert.Equal(t, "token-abc", repo.saved.Token)
	assert.Equal(t, token.Platform("ANDROID"), repo.saved.Platform)
}

func TestService_DeleteToken(t *testing.T) {
	repo := &mockRepo{}
	svc := token.NewService(repo, &mockFCM{}, &mockPref{enabled: true})

	err := svc.DeleteToken(context.Background(), 1, "token-abc")
	require.NoError(t, err)
	assert.Equal(t, "token-abc", repo.deleted)
}

func TestService_Notify_sendsToAllUsers(t *testing.T) {
	repo := &mockRepo{tokens: map[int64][]token.DeviceToken{
		1: {{Token: "t1", AccountID: 1}},
		2: {{Token: "t2", AccountID: 2}},
	}}
	fcm := &mockFCM{}
	svc := token.NewService(repo, fcm, &mockPref{enabled: true})

	err := svc.Notify(context.Background(), []int64{1, 2}, "title", "body", 0)
	require.NoError(t, err)
	assert.ElementsMatch(t, []string{"t1", "t2"}, fcm.calledTokens)
}

func TestService_Notify_skipsDisabledChannel(t *testing.T) {
	repo := &mockRepo{tokens: map[int64][]token.DeviceToken{
		1: {{Token: "t1", AccountID: 1}},
	}}
	fcm := &mockFCM{}
	svc := token.NewService(repo, fcm, &mockPref{enabled: false})

	err := svc.Notify(context.Background(), []int64{1}, "title", "body", 42)
	require.NoError(t, err)
	assert.Nil(t, fcm.calledTokens)
}

func TestService_Notify_deletesInvalidTokens(t *testing.T) {
	repo := &mockRepo{tokens: map[int64][]token.DeviceToken{
		1: {{Token: "bad-token", AccountID: 1}},
	}}
	fcm := &mockFCM{invalid: []string{"bad-token"}}
	svc := token.NewService(repo, fcm, &mockPref{enabled: true})

	err := svc.Notify(context.Background(), []int64{1}, "title", "body", 0)
	require.NoError(t, err)
	assert.Equal(t, "bad-token", repo.deleted)
}

func TestService_Notify_preferenceFailDefaultsToEnabled(t *testing.T) {
	repo := &mockRepo{tokens: map[int64][]token.DeviceToken{
		1: {{Token: "t1", AccountID: 1}},
	}}
	fcm := &mockFCM{}
	pref := &mockPref{err: errors.New("preference service unreachable")}
	svc := token.NewService(repo, fcm, pref)

	err := svc.Notify(context.Background(), []int64{1}, "title", "body", 42)
	require.NoError(t, err)
	assert.Equal(t, []string{"t1"}, fcm.calledTokens)
}
