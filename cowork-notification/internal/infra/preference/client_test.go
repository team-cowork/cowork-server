package preference_test

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/cowork/cowork-notification/internal/infra/preference"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestClient_IsNotificationEnabled_true(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, "/accounts/1/channels/2/notification", r.URL.Path)
		json.NewEncoder(w).Encode(map[string]bool{"notification": true})
	}))
	defer srv.Close()

	c := preference.NewClient(srv.URL)
	enabled, err := c.IsNotificationEnabled(context.Background(), 1, 2)
	require.NoError(t, err)
	assert.True(t, enabled)
}

func TestClient_IsNotificationEnabled_false(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		json.NewEncoder(w).Encode(map[string]bool{"notification": false})
	}))
	defer srv.Close()

	c := preference.NewClient(srv.URL)
	enabled, err := c.IsNotificationEnabled(context.Background(), 1, 2)
	require.NoError(t, err)
	assert.False(t, enabled)
}

func TestClient_IsNotificationEnabled_serverError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer srv.Close()

	c := preference.NewClient(srv.URL)
	_, err := c.IsNotificationEnabled(context.Background(), 1, 2)
	assert.Error(t, err)
}

func TestClient_AreNotificationsEnabled(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, "/channels/2/notifications", r.URL.Path)
		ids := r.URL.Query()["accountIds"]
		assert.ElementsMatch(t, []string{"1", "3"}, ids)
		json.NewEncoder(w).Encode(map[string]bool{"1": true, "3": false})
	}))
	defer srv.Close()

	c := preference.NewClient(srv.URL)
	result, err := c.AreNotificationsEnabled(context.Background(), []int64{1, 3}, 2)
	require.NoError(t, err)
	assert.Equal(t, map[int64]bool{1: true, 3: false}, result)
}
