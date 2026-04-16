package channel

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"net/http"
	"time"

	"github.com/cowork/cowork-voice/internal/apperror"
)

type memberResponse struct {
	TeamID int64 `json:"team_id"`
}

type Client struct {
	http    *http.Client
	baseURL string
}

func NewClient(baseURL string) *Client {
	return &Client{
		http:    &http.Client{Timeout: 5 * time.Second},
		baseURL: baseURL,
	}
}

func (c *Client) VerifyMembership(ctx context.Context, channelID, userID int64) (int64, error) {
	url := fmt.Sprintf("%s/internal/channels/%d/members/%d", c.baseURL, channelID, userID)
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return 0, apperror.Internal(err.Error())
	}
	resp, err := c.http.Do(req)
	if err != nil {
		slog.Error("channel service request failed", "err", err)
		return 0, apperror.ServiceUnavailable("channel service connection failed")
	}
	defer resp.Body.Close()

	switch resp.StatusCode {
	case http.StatusOK:
		var body memberResponse
		if err := json.NewDecoder(resp.Body).Decode(&body); err != nil {
			return 0, apperror.Internal(err.Error())
		}
		return body.TeamID, nil
	case http.StatusNotFound, http.StatusForbidden:
		return 0, apperror.NotMember()
	default:
		return 0, apperror.ServiceUnavailable(fmt.Sprintf("channel service returned %d", resp.StatusCode))
	}
}
