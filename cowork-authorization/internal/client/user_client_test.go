package client

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"strings"
	"testing"
)

type roundTripFunc func(*http.Request) (*http.Response, error)

func (fn roundTripFunc) RoundTrip(request *http.Request) (*http.Response, error) {
	return fn(request)
}

func TestUpsertFallsBackToLegacyCommandOnlyForRollingDeployNotFound(t *testing.T) {
	t.Parallel()

	paths := make([]string, 0, 2)
	statuses := make([]any, 0, 2)
	client := NewUserClient("http://cowork-user:8082")
	client.httpClient = &http.Client{Transport: roundTripFunc(func(request *http.Request) (*http.Response, error) {
		paths = append(paths, request.URL.Path)
		var payload map[string]any
		if err := json.NewDecoder(request.Body).Decode(&payload); err != nil {
			t.Fatal(err)
		}
		statuses = append(statuses, payload["status"])
		if request.URL.Path == "/internal/users/42" {
			return response(http.StatusNotFound, `{}`), nil
		}
		return response(http.StatusOK, `{"id":42}`), nil
	})}

	id, err := client.Upsert(context.Background(), 42, UpsertUserRequest{Name: "user"})
	if err != nil {
		t.Fatal(err)
	}
	if id != 42 {
		t.Fatalf("id = %d, want 42", id)
	}
	if got, want := strings.Join(paths, ","), "/internal/users/42,/users/42"; got != want {
		t.Fatalf("paths = %q, want %q", got, want)
	}
	if statuses[0] != nil || statuses[1] != "online" {
		t.Fatalf("presence fields = %#v, want internal absent and legacy online", statuses)
	}
}

func TestUpsertDoesNotReplayValidationOrTransientFailureToLegacyPath(t *testing.T) {
	t.Parallel()

	for _, status := range []int{http.StatusBadRequest, http.StatusServiceUnavailable} {
		status := status
		t.Run(http.StatusText(status), func(t *testing.T) {
			t.Parallel()

			calls := 0
			client := NewUserClient("http://cowork-user:8082")
			client.httpClient = &http.Client{Transport: roundTripFunc(func(_ *http.Request) (*http.Response, error) {
				calls++
				return response(status, `{}`), nil
			})}

			if _, err := client.Upsert(context.Background(), 42, UpsertUserRequest{Name: "user"}); err == nil {
				t.Fatal("Upsert() error = nil")
			}
			if calls != 1 {
				t.Fatalf("calls = %d, want one non-replayed command", calls)
			}
		})
	}
}

func response(status int, body string) *http.Response {
	return &http.Response{
		StatusCode: status,
		Body:       io.NopCloser(strings.NewReader(body)),
		Header:     make(http.Header),
	}
}
