package user

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"time"
)

type Client struct {
	baseURL    string
	httpClient *http.Client
}

func NewClient(baseURL string) *Client {
	return &Client{
		baseURL:    baseURL,
		httpClient: &http.Client{Timeout: 3 * time.Second},
	}
}

// GetDisplayName returns nickname if set, otherwise name.
func (c *Client) GetDisplayName(ctx context.Context, userID int64) (string, error) {
	url := fmt.Sprintf("%s/users/%d", c.baseURL, userID)
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return "", err
	}

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("user service returned status %d", resp.StatusCode)
	}

	var body struct {
		Name     string  `json:"name"`
		Nickname *string `json:"nickname"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&body); err != nil {
		return "", err
	}

	if body.Nickname != nil && *body.Nickname != "" {
		return *body.Nickname, nil
	}
	return body.Name, nil
}
