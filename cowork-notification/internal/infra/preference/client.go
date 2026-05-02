package preference

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"strconv"
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

func (c *Client) AreNotificationsEnabled(ctx context.Context, accountIDs []int64, channelID int64) (map[int64]bool, error) {
	params := url.Values{}
	for _, id := range accountIDs {
		params.Add("accountIds", strconv.FormatInt(id, 10))
	}
	reqURL := fmt.Sprintf("%s/channels/%d/notifications?%s", c.baseURL, channelID, params.Encode())
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, reqURL, nil)
	if err != nil {
		return nil, err
	}
	resp, err := c.httpClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("preference service returned status %d", resp.StatusCode)
	}
	var raw map[string]bool
	if err := json.NewDecoder(resp.Body).Decode(&raw); err != nil {
		return nil, err
	}
	result := make(map[int64]bool, len(raw))
	for k, v := range raw {
		id, err := strconv.ParseInt(k, 10, 64)
		if err != nil {
			continue
		}
		result[id] = v
	}
	return result, nil
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
