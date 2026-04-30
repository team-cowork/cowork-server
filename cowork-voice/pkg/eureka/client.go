package eureka

import (
	"bytes"
	"encoding/json"
	"fmt"
	"log/slog"
	"net/http"
	"strings"
	"time"

	"github.com/cowork/cowork-voice/internal/config"
)

type Client struct {
	httpClient *http.Client
	serverURL  string
	appName    string
	instanceID string
	stopCh     chan struct{}
}

func New(cfg *config.AppConfig) *Client {
	instanceID := fmt.Sprintf("%s:%s:%d", cfg.EurekaInstanceHost, cfg.EurekaAppName, cfg.EurekaInstancePort)
	return &Client{
		httpClient: &http.Client{Timeout: 5 * time.Second},
		serverURL:  strings.TrimRight(cfg.EurekaServerURL, "/"),
		appName:    cfg.EurekaAppName,
		instanceID: instanceID,
		stopCh:     make(chan struct{}),
	}
}

func (c *Client) Register(cfg *config.AppConfig) error {
	if !cfg.EurekaEnabled {
		return nil
	}

	body := map[string]any{
		"instance": map[string]any{
			"instanceId":       c.instanceID,
			"hostName":         cfg.EurekaInstanceHost,
			"app":              strings.ToUpper(cfg.EurekaAppName),
			"ipAddr":           cfg.EurekaInstanceHost,
			"vipAddress":       cfg.EurekaAppName,
			"secureVipAddress": cfg.EurekaAppName,
			"status":           "UP",
			"port":             map[string]any{"$": cfg.EurekaInstancePort, "@enabled": "true"},
			"securePort":       map[string]any{"$": 443, "@enabled": "false"},
			"healthCheckUrl":   fmt.Sprintf("http://%s:%d/health", cfg.EurekaInstanceHost, cfg.EurekaInstancePort),
			"statusPageUrl":    fmt.Sprintf("http://%s:%d/health", cfg.EurekaInstanceHost, cfg.EurekaInstancePort),
			"homePageUrl":      fmt.Sprintf("http://%s:%d/", cfg.EurekaInstanceHost, cfg.EurekaInstancePort),
			"dataCenterInfo": map[string]any{
				"@class": "com.netflix.appinfo.InstanceInfo$DefaultDataCenterInfo",
				"name":   "MyOwn",
			},
			"metadata": map[string]string{
				"management.port":   fmt.Sprintf("%d", cfg.EurekaInstancePort),
				"prometheus.scrape": "true",
				"prometheus.path":   "/metrics",
				"status":            "UP",
			},
		},
	}

	payload, err := json.Marshal(body)
	if err != nil {
		return err
	}
	req, err := http.NewRequest(http.MethodPost, c.serverURL+"/apps/"+cfg.EurekaAppName, bytes.NewReader(payload))
	if err != nil {
		return err
	}
	req.Header.Set("Accept", "application/json")
	req.Header.Set("Content-Type", "application/json")

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return fmt.Errorf("eureka register failed: status=%d", resp.StatusCode)
	}
	slog.Info("registered with eureka", "app", cfg.EurekaAppName, "instance", c.instanceID)
	return nil
}

func (c *Client) StartHeartbeat(cfg *config.AppConfig) {
	if !cfg.EurekaEnabled {
		return
	}

	interval := time.Duration(cfg.EurekaHeartbeatIntervalSecs) * time.Second
	ticker := time.NewTicker(interval)
	go func() {
		defer ticker.Stop()
		for {
			select {
			case <-c.stopCh:
				return
			case <-ticker.C:
				if err := c.heartbeat(); err != nil {
					slog.Warn("eureka heartbeat failed", "err", err)
					if regErr := c.Register(cfg); regErr != nil {
						slog.Warn("eureka re-registration failed", "err", regErr)
					}
				}
			}
		}
	}()
}

func (c *Client) Deregister(cfg *config.AppConfig) error {
	if !cfg.EurekaEnabled {
		return nil
	}

	select {
	case <-c.stopCh:
		// already closed
	default:
		close(c.stopCh)
	}

	req, err := http.NewRequest(http.MethodDelete, c.instanceURL(), nil)
	if err != nil {
		return err
	}
	resp, err := c.httpClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return fmt.Errorf("eureka deregister failed: status=%d", resp.StatusCode)
	}
	slog.Info("deregistered from eureka", "app", c.appName, "instance", c.instanceID)
	return nil
}

func (c *Client) heartbeat() error {
	req, err := http.NewRequest(http.MethodPut, c.instanceURL(), nil)
	if err != nil {
		return err
	}
	resp, err := c.httpClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return fmt.Errorf("eureka heartbeat failed: status=%d", resp.StatusCode)
	}
	return nil
}

func (c *Client) instanceURL() string {
	return c.serverURL + "/apps/" + c.appName + "/" + c.instanceID
}
