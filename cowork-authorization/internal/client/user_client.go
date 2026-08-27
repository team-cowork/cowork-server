package client

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"time"
)

type UserClient struct {
	baseURL    string
	httpClient *http.Client
}

func NewUserClient(baseURL string) *UserClient {
	return &UserClient{
		baseURL:    baseURL,
		httpClient: &http.Client{Timeout: 5 * time.Second},
	}
}

// UpsertUserRequest intentionally contains only identity/profile fields.
// user.presence.event is the sole authority for online/offline state.
type UpsertUserRequest struct {
	Name                 string  `json:"name"`
	Email                string  `json:"email"`
	Sex                  string  `json:"sex"`
	Grade                *int8   `json:"grade"`
	ClassNumber          *int8   `json:"class_number"`
	StudentNumberInClass *int8   `json:"student_number_in_class"`
	Major                string  `json:"major"`
	Role                 string  `json:"role"`
	GithubID             *string `json:"github_id"`
	DataGSMStudentID     *int64  `json:"datagsm_student_id"`
}

type upsertUserResponse struct {
	ID int64 `json:"id"`
}

const maxUpsertResponseBytes = 1 << 20

// Upsert is an intentional synchronous internal-HTTP exception: token issuance must not complete
// before cowork-user has durably accepted the account, otherwise the first authenticated request
// can race user creation. Replace this only together with an acknowledged login-readiness protocol.
// It prefers PUT {baseURL}/internal/users/{userId}. During the rolling migration
// from the former /users/{userId} command it falls back only when the old user
// instance returns 404; validation and transient failures are never replayed.
func (c *UserClient) Upsert(ctx context.Context, userId int64, req UpsertUserRequest) (int64, error) {
	body, err := json.Marshal(req)
	if err != nil {
		return 0, fmt.Errorf("failed to marshal upsert request: %w", err)
	}
	legacyBody, err := json.Marshal(struct {
		UpsertUserRequest
		Status string `json:"status"`
	}{UpsertUserRequest: req, Status: "online"})
	if err != nil {
		return 0, fmt.Errorf("failed to marshal legacy upsert request: %w", err)
	}

	paths := []string{
		fmt.Sprintf("/internal/users/%d", userId),
		fmt.Sprintf("/users/%d", userId),
	}
	for index, path := range paths {
		requestBody := body
		if index == 1 {
			// Old cowork-user has no presence consumer and historically derives
			// login presence from this field. New cowork-user ignores it.
			requestBody = legacyBody
		}
		status, responseBody, err := c.putUpsert(ctx, path, requestBody)
		if err != nil {
			return 0, err
		}
		if status >= http.StatusOK && status < http.StatusMultipleChoices {
			var result upsertUserResponse
			if err := json.Unmarshal(responseBody, &result); err != nil {
				return 0, fmt.Errorf("failed to parse upsert response: %w", err)
			}
			return result.ID, nil
		}
		if index == 0 && status == http.StatusNotFound {
			continue
		}
		return 0, fmt.Errorf("upsert returned non-2xx status %d", status)
	}
	return 0, fmt.Errorf("upsert endpoint not found")
}

func (c *UserClient) putUpsert(ctx context.Context, path string, body []byte) (int, []byte, error) {
	url := c.baseURL + path
	httpReq, err := http.NewRequestWithContext(ctx, http.MethodPut, url, bytes.NewReader(body))
	if err != nil {
		return 0, nil, fmt.Errorf("failed to create upsert request: %w", err)
	}
	httpReq.Header.Set("Content-Type", "application/json")

	resp, err := c.httpClient.Do(httpReq)
	if err != nil {
		return 0, nil, fmt.Errorf("upsert request failed: %w", err)
	}
	defer func() { _ = resp.Body.Close() }()

	respBody, err := io.ReadAll(io.LimitReader(resp.Body, maxUpsertResponseBytes))
	if err != nil {
		return 0, nil, fmt.Errorf("failed to read upsert response: %w", err)
	}
	return resp.StatusCode, respBody, nil
}
