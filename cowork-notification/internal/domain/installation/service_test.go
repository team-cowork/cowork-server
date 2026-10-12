package installation_test

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/cowork/cowork-notification/internal/apperr"
	"github.com/cowork/cowork-notification/internal/domain/delivery"
	"github.com/cowork/cowork-notification/internal/domain/installation"
	"github.com/cowork/cowork-notification/internal/infra/fcm"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

type mockRepo struct {
	installations map[int64][]installation.Installation
	err           error
	nextID        int64
	registerCalls int
}

func (m *mockRepo) Register(_ context.Context, requested *installation.Installation) (installation.RegistrationResult, error) {
	m.registerCalls++
	if m.err != nil {
		return installation.RegistrationResult{}, m.err
	}
	if m.installations == nil {
		m.installations = make(map[int64][]installation.Installation)
	}

	var previousAccountID int64
	for accountID, installations := range m.installations {
		for i, existing := range installations {
			if existing.FID != requested.FID {
				continue
			}
			if accountID == requested.AccountID {
				existing.Platform = requested.Platform
				m.installations[accountID][i] = existing
				requested.ID = existing.ID
				return installation.RegistrationResult{InstallationID: existing.ID}, nil
			}

			previousAccountID = accountID
			m.installations[accountID] = append(installations[:i], installations[i+1:]...)
			break
		}
	}

	for _, installations := range m.installations {
		for _, existing := range installations {
			if existing.ID > m.nextID {
				m.nextID = existing.ID
			}
		}
	}
	m.nextID++
	requested.ID = m.nextID
	m.installations[requested.AccountID] = append(m.installations[requested.AccountID], *requested)
	return installation.RegistrationResult{
		InstallationID:    requested.ID,
		PreviousAccountID: previousAccountID,
		Reassigned:        previousAccountID != 0,
	}, nil
}
func (m *mockRepo) FindByID(_ context.Context, _ int64) (*installation.Installation, error) {
	return nil, m.err
}
func (m *mockRepo) FindByAccountIDs(_ context.Context, ids []int64) (map[int64][]installation.Installation, error) {
	result := make(map[int64][]installation.Installation)
	for _, id := range ids {
		if ts, ok := m.installations[id]; ok {
			result[id] = ts
		}
	}
	return result, m.err
}
func (m *mockRepo) DeleteByFIDs(_ context.Context, _ []string) error {
	return m.err
}
func (m *mockRepo) DeleteByAccountIDAndFID(_ context.Context, accountID int64, value string) error {
	if m.err != nil {
		return m.err
	}
	for i, existing := range m.installations[accountID] {
		if existing.FID == value {
			m.installations[accountID] = append(m.installations[accountID][:i], m.installations[accountID][i+1:]...)
			return nil
		}
	}
	return apperr.NotFound("installation not found")
}

type mockFCM struct {
	calledFIDs []string
	calledData []map[string]string
	err        error
}

func (m *mockFCM) Send(_ context.Context, messages []fcm.Message, _, _ string) ([]fcm.Result, error) {
	for _, msg := range messages {
		m.calledFIDs = append(m.calledFIDs, msg.FID)
		m.calledData = append(m.calledData, msg.Data)
	}
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

// snapshotRepo returns one installation snapshot per FindByAccountIDs call so a test can
// change ownership between Notify's first lookup and its pre-send re-verification.
// reverifyErr fails every call after the first.
type snapshotRepo struct {
	mockRepo
	snapshots   []map[int64][]installation.Installation
	reverifyErr error
	calls       int
}

func (m *snapshotRepo) FindByAccountIDs(_ context.Context, _ []int64) (map[int64][]installation.Installation, error) {
	m.calls++
	if m.calls > 1 && m.reverifyErr != nil {
		return nil, m.reverifyErr
	}
	snapshot := m.snapshots[0]
	if len(m.snapshots) > 1 {
		m.snapshots = m.snapshots[1:]
	}
	return snapshot, nil
}

type mockDelivery struct {
	cancelled []string
}

func (m *mockDelivery) ResumeOrCreate(_ context.Context, _ string, targets []delivery.Target, _, _ string, _ map[string]string) ([]delivery.Target, error) {
	claimed := make([]delivery.Target, len(targets))
	for i, t := range targets {
		t.ClaimToken = "claim-" + t.FID
		claimed[i] = t
	}
	return claimed, nil
}
func (m *mockDelivery) FinalizeSuccess(context.Context, string, int64, string) error { return nil }
func (m *mockDelivery) FinalizeInvalid(context.Context, string, int64, string) error { return nil }
func (m *mockDelivery) FinalizeFailure(context.Context, string, int64, string, delivery.ErrorClass, time.Time) (delivery.Status, error) {
	return delivery.StatusPendingRetry, nil
}
func (m *mockDelivery) FinalizeCancelled(_ context.Context, _ string, _ int64, claimToken string) error {
	m.cancelled = append(m.cancelled, claimToken)
	return nil
}

func TestServiceNotifyReverifiesOwnershipBeforeSend(t *testing.T) {
	before := map[int64][]installation.Installation{
		1: {{ID: 10, AccountID: 1, FID: "moved"}, {ID: 11, AccountID: 1, FID: "deleted"}, {ID: 12, AccountID: 1, FID: "kept"}},
	}
	// "moved" now belongs to account 2 under a new generation; "deleted" is gone.
	after := map[int64][]installation.Installation{
		1: {{ID: 12, AccountID: 1, FID: "kept"}},
	}

	t.Run("durable delivery sends only current generations and cancels the stale claims", func(t *testing.T) {
		repo := &snapshotRepo{snapshots: []map[int64][]installation.Installation{before, after}}
		fcm := &mockFCM{}
		ledger := &mockDelivery{}
		svc := installation.NewService(repo, fcm, &mockPref{enabled: true}, ledger)

		_, err := svc.Notify(context.Background(), "event-1", []int64{1}, nil, "title", "body", 0)

		require.NoError(t, err)
		assert.Equal(t, []string{"kept"}, fcm.calledFIDs)
		assert.Equal(t, []map[string]string{{"accountId": "1"}}, fcm.calledData)
		assert.ElementsMatch(t, []string{"claim-moved", "claim-deleted"}, ledger.cancelled)
	})

	t.Run("non-durable delivery skips stale generations", func(t *testing.T) {
		repo := &snapshotRepo{snapshots: []map[int64][]installation.Installation{before, after}}
		fcm := &mockFCM{}
		svc := installation.NewService(repo, fcm, &mockPref{enabled: true}, nil)

		_, err := svc.Notify(context.Background(), "", []int64{1}, nil, "title", "body", 0)

		require.NoError(t, err)
		assert.Equal(t, []string{"kept"}, fcm.calledFIDs)
	})

	t.Run("nothing is sent when every generation went stale", func(t *testing.T) {
		repo := &snapshotRepo{snapshots: []map[int64][]installation.Installation{before, {}}}
		fcm := &mockFCM{}
		ledger := &mockDelivery{}
		svc := installation.NewService(repo, fcm, &mockPref{enabled: true}, ledger)

		_, err := svc.Notify(context.Background(), "event-1", []int64{1}, nil, "title", "body", 0)

		require.NoError(t, err)
		assert.Nil(t, fcm.calledFIDs)
		assert.Len(t, ledger.cancelled, 3)
	})

	t.Run("a failed re-verification sends nothing and leaves the claims for reclaim", func(t *testing.T) {
		repo := &snapshotRepo{snapshots: []map[int64][]installation.Installation{before}, reverifyErr: errors.New("db down")}
		fcm := &mockFCM{}
		ledger := &mockDelivery{}
		svc := installation.NewService(repo, fcm, &mockPref{enabled: true}, ledger)

		_, err := svc.Notify(context.Background(), "event-1", []int64{1}, nil, "title", "body", 0)

		require.ErrorIs(t, err, repo.reverifyErr)
		assert.Nil(t, fcm.calledFIDs)
		assert.Empty(t, ledger.cancelled)
	})
}

// deviceFID is a well-formed Firebase installation ID (22 URL-safe base64 characters
// starting with c, d, e or f).
const deviceFID = "cXk9Y2Rl-ZWZnaGlqa2xt_"

func TestServiceRegisterInstallationRejectsNonFID(t *testing.T) {
	cases := map[string]string{
		"legacy registration token": "cXk9Y2Rl-ZWZnaGlqa2xt_:APA91bHun4MxP5egoKMwt2KZFBaFUH-1RYqx",
		"too short":                 "cXk9Y2Rl-ZWZnaGlqa2xt",
		"too long":                  deviceFID + "A",
		"invalid prefix":            "aXk9Y2Rl-ZWZnaGlqa2xt_",
		"non URL-safe character":    "cXk9Y2Rl+ZWZnaGlqa2xt_",
	}
	for name, value := range cases {
		t.Run(name, func(t *testing.T) {
			repo := &mockRepo{}
			svc := installation.NewService(repo, &mockFCM{}, &mockPref{}, nil)

			registerErr := svc.RegisterInstallation(context.Background(), 1, value, "ANDROID")
			deleteErr := svc.DeleteInstallation(context.Background(), 1, value)

			for _, err := range []error{registerErr, deleteErr} {
				var appErr *apperr.AppError
				require.ErrorAs(t, err, &appErr)
				assert.Equal(t, 400, appErr.Code)
			}
			assert.Zero(t, repo.registerCalls)
		})
	}
}

func TestServiceRegisterInstallationOwnership(t *testing.T) {
	t.Run("rejects an unsupported platform before persistence", func(t *testing.T) {
		repo := &mockRepo{}
		svc := installation.NewService(repo, &mockFCM{}, &mockPref{}, nil)

		err := svc.RegisterInstallation(context.Background(), 1, deviceFID, "DESKTOP")

		var appErr *apperr.AppError
		require.ErrorAs(t, err, &appErr)
		assert.Equal(t, 400, appErr.Code)
		assert.Zero(t, repo.registerCalls)
	})

	t.Run("refreshes the same owner without changing the installation generation", func(t *testing.T) {
		repo := &mockRepo{installations: map[int64][]installation.Installation{
			1: {{ID: 10, AccountID: 1, FID: deviceFID, Platform: installation.PlatformWeb}},
		}}
		svc := installation.NewService(repo, &mockFCM{}, &mockPref{}, nil)

		err := svc.RegisterInstallation(context.Background(), 1, deviceFID, "ANDROID")

		require.NoError(t, err)
		require.Len(t, repo.installations[1], 1)
		assert.Equal(t, int64(10), repo.installations[1][0].ID)
		assert.Equal(t, installation.PlatformAndroid, repo.installations[1][0].Platform)
	})

	t.Run("moves ownership to a new account and excludes the previous account", func(t *testing.T) {
		repo := &mockRepo{installations: map[int64][]installation.Installation{
			1: {{ID: 10, AccountID: 1, FID: deviceFID, Platform: installation.PlatformIOS}},
		}}
		fcm := &mockFCM{}
		svc := installation.NewService(repo, fcm, &mockPref{enabled: true}, nil)

		err := svc.RegisterInstallation(context.Background(), 2, deviceFID, "IOS")
		require.NoError(t, err)
		assert.Empty(t, repo.installations[1])
		require.Len(t, repo.installations[2], 1)
		assert.NotEqual(t, int64(10), repo.installations[2][0].ID)

		_, err = svc.Notify(context.Background(), "", []int64{1, 2}, nil, "title", "body", 0)
		require.NoError(t, err)
		assert.Equal(t, []string{deviceFID}, fcm.calledFIDs)

		require.Error(t, svc.DeleteInstallation(context.Background(), 1, deviceFID))
		require.NoError(t, svc.DeleteInstallation(context.Background(), 2, deviceFID))
		assert.Empty(t, repo.installations[2])
	})
}

func TestServiceNotifyAccordingToRecipientPreference(t *testing.T) {
	t.Run("enabled recipients receive notifications", func(t *testing.T) {
		repo := &mockRepo{installations: map[int64][]installation.Installation{
			1: {{FID: "t1", AccountID: 1}},
			2: {{FID: "t2", AccountID: 2}},
		}}
		fcm := &mockFCM{}
		svc := installation.NewService(repo, fcm, &mockPref{enabled: true}, nil)

		_, err := svc.Notify(context.Background(), "", []int64{1, 2}, nil, "title", "body", 0)

		require.NoError(t, err)
		assert.ElementsMatch(t, []string{"t1", "t2"}, fcm.calledFIDs)
	})

	t.Run("muted recipients are excluded", func(t *testing.T) {
		repo := &mockRepo{installations: map[int64][]installation.Installation{
			1: {{FID: "t1", AccountID: 1}},
		}}
		fcm := &mockFCM{}
		svc := installation.NewService(repo, fcm, &mockPref{enabled: false}, nil)

		enabledIDs, err := svc.Notify(context.Background(), "", []int64{1}, nil, "title", "body", 42)

		require.NoError(t, err)
		assert.Nil(t, fcm.calledFIDs)
		assert.Empty(t, enabledIDs)
	})

	t.Run("forced recipients bypass mute", func(t *testing.T) {
		repo := &mockRepo{installations: map[int64][]installation.Installation{
			1: {{FID: "t1", AccountID: 1}},
			2: {{FID: "t2", AccountID: 2}},
		}}
		fcm := &mockFCM{}
		svc := installation.NewService(repo, fcm, &mockPref{enabled: false}, nil)

		enabledIDs, err := svc.Notify(context.Background(), "", []int64{1, 2}, []int64{2}, "title", "body", 42)

		require.NoError(t, err)
		assert.Equal(t, []string{"t2"}, fcm.calledFIDs)
		assert.Equal(t, []int64{2}, enabledIDs)
	})

	t.Run("duplicate target and forced recipients receive one notification", func(t *testing.T) {
		repo := &mockRepo{installations: map[int64][]installation.Installation{
			1: {{FID: "t1", AccountID: 1}},
			2: {{FID: "t2", AccountID: 2}},
		}}
		fcm := &mockFCM{}
		svc := installation.NewService(repo, fcm, &mockPref{enabled: true}, nil)

		enabledIDs, err := svc.Notify(context.Background(), "", []int64{1, 1, 2}, []int64{2, 2}, "title", "body", 42)

		require.NoError(t, err)
		assert.ElementsMatch(t, []string{"t1", "t2"}, fcm.calledFIDs)
		assert.Equal(t, []int64{2, 1}, enabledIDs)
	})

	t.Run("preference lookup failure fails closed without sending", func(t *testing.T) {
		repo := &mockRepo{installations: map[int64][]installation.Installation{
			1: {{FID: "t1", AccountID: 1}},
		}}
		fcm := &mockFCM{}
		pref := &mockPref{err: errors.New("preference service unreachable")}
		svc := installation.NewService(repo, fcm, pref, nil)

		_, err := svc.Notify(context.Background(), "", []int64{1}, nil, "title", "body", 42)

		require.Error(t, err)
		assert.Nil(t, fcm.calledFIDs)
	})
}
