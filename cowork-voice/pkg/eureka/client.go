package eureka

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/cowork/cowork-voice/internal/config"
)

const (
	statusUp           = "UP"
	statusOutOfService = "OUT_OF_SERVICE"
	eurekaRetryDelay   = time.Second
)

type StatusSource interface {
	Snapshot() (bool, <-chan struct{})
}

type Client struct {
	httpClient *http.Client
	serverURL  string
	appName    string
	instanceID string
}

func New(cfg *config.AppConfig) *Client {
	return &Client{
		httpClient: &http.Client{Timeout: 5 * time.Second},
		serverURL:  strings.TrimRight(cfg.EurekaServerURL, "/"),
		appName:    cfg.EurekaAppName,
		instanceID: cfg.EurekaInstanceID,
	}
}

// Run owns the complete Eureka lifecycle. All registration, status updates,
// heartbeat recovery, and shutdown requests are serialized in this goroutine
// so an older readiness transition cannot overwrite a newer one.
func (c *Client) Run(ctx context.Context, cfg *config.AppConfig, source StatusSource) {
	if !cfg.EurekaEnabled {
		return
	}

	heartbeatInterval := time.Duration(cfg.EurekaHeartbeatIntervalSecs) * time.Second
	if heartbeatInterval <= 0 {
		heartbeatInterval = 30 * time.Second
	}
	heartbeatTicker := time.NewTicker(heartbeatInterval)
	defer heartbeatTicker.Stop()

	registered := false
	registeredStatus := ""
	defer func() { c.shutdown(cfg, registered) }()

	for {
		ready, changed := source.Snapshot()
		desiredStatus := statusForReadiness(ready)

		if !registered {
			if err := c.register(ctx, cfg, desiredStatus); err != nil {
				if ctx.Err() != nil {
					return
				}
				slog.Warn("eureka registration failed; retrying", "status", desiredStatus, "err", err)
				if !waitForRetry(ctx, changed) {
					return
				}
				continue
			}
			registered = true
			registeredStatus = desiredStatus
			// Re-snapshot immediately in case readiness changed while registration
			// was in flight.
			continue
		}

		if registeredStatus != desiredStatus {
			if err := c.updateStatus(ctx, desiredStatus); err != nil {
				if ctx.Err() != nil {
					return
				}
				slog.Warn("eureka status update failed; removing stale registration", "status", desiredStatus, "err", err)
				c.bestEffortDeregister()
				registered = false
				registeredStatus = ""
				if !waitForRetry(ctx, changed) {
					return
				}
				continue
			}
			registeredStatus = desiredStatus
			continue
		}

		select {
		case <-ctx.Done():
			return
		case <-changed:
			continue
		case <-heartbeatTicker.C:
			if err := c.heartbeat(ctx); err != nil {
				if ctx.Err() != nil {
					return
				}
				slog.Warn("eureka heartbeat failed; re-registering current readiness", "err", err)
				c.bestEffortDeregister()
				registered = false
				registeredStatus = ""
				if !waitForRetry(ctx, changed) {
					return
				}
			}
		}
	}
}

func (c *Client) register(ctx context.Context, cfg *config.AppConfig, status string) error {
	body := map[string]any{
		"instance": map[string]any{
			"instanceId":       c.instanceID,
			"hostName":         cfg.EurekaInstanceHost,
			"app":              strings.ToUpper(cfg.EurekaAppName),
			"ipAddr":           cfg.EurekaInstanceHost,
			"vipAddress":       cfg.EurekaAppName,
			"secureVipAddress": cfg.EurekaAppName,
			"status":           status,
			"port":             map[string]any{"$": cfg.EurekaInstancePort, "@enabled": "true"},
			"securePort":       map[string]any{"$": 443, "@enabled": "false"},
			"healthCheckUrl": fmt.Sprintf(
				"http://%s:%d/health/ready",
				cfg.EurekaInstanceHost,
				cfg.EurekaInstancePort,
			),
			"statusPageUrl": fmt.Sprintf(
				"http://%s:%d/health",
				cfg.EurekaInstanceHost,
				cfg.EurekaInstancePort,
			),
			"homePageUrl": fmt.Sprintf(
				"http://%s:%d/",
				cfg.EurekaInstanceHost,
				cfg.EurekaInstancePort,
			),
			"dataCenterInfo": map[string]any{
				"@class": "com.netflix.appinfo.InstanceInfo$DefaultDataCenterInfo",
				"name":   "MyOwn",
			},
			"metadata": map[string]string{
				"management.port":   fmt.Sprintf("%d", cfg.EurekaInstancePort),
				"prometheus.scrape": "true",
				"prometheus.path":   "/metrics",
			},
		},
	}

	payload, err := json.Marshal(body)
	if err != nil {
		return err
	}
	req, err := http.NewRequestWithContext(
		ctx,
		http.MethodPost,
		c.serverURL+"/apps/"+cfg.EurekaAppName,
		bytes.NewReader(payload),
	)
	if err != nil {
		return err
	}
	req.Header.Set("Accept", "application/json")
	req.Header.Set("Content-Type", "application/json")
	if err := c.do(req, "register"); err != nil {
		return err
	}
	slog.Info("registered with eureka", "app", cfg.EurekaAppName, "instance", c.instanceID, "status", status)
	return nil
}

func (c *Client) updateStatus(ctx context.Context, status string) error {
	query := url.Values{"value": []string{status}}
	req, err := http.NewRequestWithContext(
		ctx,
		http.MethodPut,
		c.instanceURL()+"/status?"+query.Encode(),
		nil,
	)
	if err != nil {
		return err
	}
	if err := c.do(req, "status update"); err != nil {
		return err
	}
	slog.Info("updated eureka status", "app", c.appName, "instance", c.instanceID, "status", status)
	return nil
}

func (c *Client) heartbeat(ctx context.Context) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodPut, c.instanceURL(), nil)
	if err != nil {
		return err
	}
	return c.do(req, "heartbeat")
}

func (c *Client) deregister(ctx context.Context) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodDelete, c.instanceURL(), nil)
	if err != nil {
		return err
	}
	if err := c.do(req, "deregister"); err != nil {
		return err
	}
	slog.Info("deregistered from eureka", "app", c.appName, "instance", c.instanceID)
	return nil
}

func (c *Client) shutdown(cfg *config.AppConfig, registered bool) {
	if !cfg.EurekaEnabled {
		return
	}
	if registered {
		ctx, cancel := context.WithTimeout(context.Background(), c.httpClient.Timeout)
		if err := c.updateStatus(ctx, statusOutOfService); err != nil {
			slog.Warn("eureka shutdown status update failed", "err", err)
		}
		cancel()
	}
	c.bestEffortDeregister()
}

func (c *Client) bestEffortDeregister() {
	ctx, cancel := context.WithTimeout(context.Background(), c.httpClient.Timeout)
	defer cancel()
	if err := c.deregister(ctx); err != nil {
		slog.Warn("eureka deregistration failed", "err", err)
	}
}

func (c *Client) do(req *http.Request, operation string) error {
	resp, err := c.httpClient.Do(req)
	if err != nil {
		return err
	}
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return fmt.Errorf("eureka %s failed: status=%d", operation, resp.StatusCode)
	}
	return nil
}

func (c *Client) instanceURL() string {
	return c.serverURL + "/apps/" + c.appName + "/" + c.instanceID
}

func statusForReadiness(ready bool) string {
	if ready {
		return statusUp
	}
	return statusOutOfService
}

func waitForRetry(ctx context.Context, changed <-chan struct{}) bool {
	timer := time.NewTimer(eurekaRetryDelay)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return false
	case <-changed:
		return true
	case <-timer.C:
		return true
	}
}
