package springconfig

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
)

type propertySource struct {
	Name   string         `json:"name"`
	Source map[string]any `json:"source"`
}

type response struct {
	Name            string           `json:"name"`
	Profiles        []string         `json:"profiles"`
	PropertySources []propertySource `json:"propertySources"`
}

// Client fetches configuration from a Spring Cloud Config Server.
type Client struct {
	baseURL    string
	appName    string
	profile    string
	httpClient *http.Client
}

func NewClient(baseURL, appName, profile string) *Client {
	return &Client{
		baseURL:    baseURL,
		appName:    appName,
		profile:    profile,
		httpClient: &http.Client{},
	}
}

// Fetch retrieves a flat key-value map from the Config Server.
// propertySources are merged in reverse order so that higher-priority sources
// (Vault, earlier in the array) win over lower-priority ones (native yml).
func (c *Client) Fetch(ctx context.Context) (map[string]string, error) {
	url := fmt.Sprintf("%s/%s/%s", c.baseURL, c.appName, c.profile)
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return nil, err
	}

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("config server returned status %d", resp.StatusCode)
	}

	var cfgResp response
	if err := json.NewDecoder(resp.Body).Decode(&cfgResp); err != nil {
		return nil, fmt.Errorf("failed to decode config server response: %w", err)
	}

	flatMap := make(map[string]string)
	for i := len(cfgResp.PropertySources) - 1; i >= 0; i-- {
		for k, v := range cfgResp.PropertySources[i].Source {
			flatMap[k] = fmt.Sprintf("%v", v)
		}
	}
	return flatMap, nil
}
