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

type UpsertUserRequest struct {
	Name     string  `json:"name"`
	Email    string  `json:"email"`
	Sex      string  `json:"sex"`
	Grade    *int8   `json:"grade"`
	Class    *int8   `json:"class"`
	ClassNum *int8   `json:"classNum"`
	Major    string  `json:"major"`
	Role     string  `json:"role"`
	GithubID *string `json:"githubId"`
}

type upsertUserResponse struct {
	ID int64 `json:"id"`
}

// Upsert calls PUT {baseURL}/users/{userId} to upsert user profile.
// Returns the userId (int64) from the response body.
func (c *UserClient) Upsert(ctx context.Context, userId int64, req UpsertUserRequest) (int64, error) {
	body, err := json.Marshal(req)
	if err != nil {
		return 0, fmt.Errorf("failed to marshal upsert request: %w", err)
	}

	url := fmt.Sprintf("%s/users/%d", c.baseURL, userId)
	httpReq, err := http.NewRequestWithContext(ctx, http.MethodPut, url, bytes.NewReader(body))
	if err != nil {
		return 0, fmt.Errorf("failed to create upsert request: %w", err)
	}
	httpReq.Header.Set("Content-Type", "application/json")

	resp, err := c.httpClient.Do(httpReq)
	if err != nil {
		return 0, fmt.Errorf("upsert request failed: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		respBody, _ := io.ReadAll(resp.Body)
		return 0, fmt.Errorf("upsert returned non-2xx status %d: %s", resp.StatusCode, string(respBody))
	}

	respBody, err := io.ReadAll(resp.Body)
	if err != nil {
		return 0, fmt.Errorf("failed to read upsert response: %w", err)
	}

	var result upsertUserResponse
	if err := json.Unmarshal(respBody, &result); err != nil {
		return 0, fmt.Errorf("failed to parse upsert response: %w", err)
	}

	return result.ID, nil
}
