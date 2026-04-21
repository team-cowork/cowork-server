package preference

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

func (c *Client) IsNotificationEnabled(ctx context.Context, accountID, channelID int64) (bool, error) {
	url := fmt.Sprintf("%s/accounts/%d/channels/%d/notification", c.baseURL, accountID, channelID)
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return true, err
	}

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return true, err
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return true, fmt.Errorf("preference service returned status %d", resp.StatusCode)
	}

	var body struct {
		Notification bool `json:"notification"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&body); err != nil {
		return true, err
	}
	return body.Notification, nil
}
