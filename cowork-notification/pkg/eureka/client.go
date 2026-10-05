package eureka

import (
	"bytes"
	"encoding/json"
	"fmt"
	"log/slog"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	eurekaclient "github.com/ArthurHlt/go-eureka-client/eureka"

	"github.com/cowork/cowork-notification/internal/config"
	"github.com/cowork/cowork-notification/internal/controlplane"
)

type Client struct {
	client    *http.Client
	serverURL string
	stopCh    chan struct{}
}

func New(cfg *config.AppConfig) *Client {
	return &Client{
		client:    controlplane.NewClient(),
		serverURL: strings.TrimRight(cfg.EurekaServerURL, "/"),
		stopCh:    make(chan struct{}),
	}
}

func (c *Client) Register(cfg *config.AppConfig) error {
	instance := eurekaclient.NewInstanceInfo(
		cfg.EurekaInstanceHost,
		cfg.EurekaAppName,
		cfg.EurekaInstanceHost,
		cfg.EurekaInstancePort,
		30,
		false,
	)
	instance.VipAddress = cfg.EurekaAppName
	instance.SecureVipAddress = cfg.EurekaAppName
	instance.InstanceID = cfg.EurekaInstanceID
	instance.HomePageUrl = fmt.Sprintf("http://%s:%d/", cfg.EurekaInstanceHost, cfg.EurekaInstancePort)
	instance.StatusPageUrl = fmt.Sprintf("http://%s:%d/health", cfg.EurekaInstanceHost, cfg.EurekaInstancePort)
	instance.HealthCheckUrl = fmt.Sprintf("http://%s:%d/health/ready", cfg.EurekaInstanceHost, cfg.EurekaInstancePort)
	instance.Metadata = &eurekaclient.MetaData{
		Map: map[string]string{
			"startup":           time.Now().String(),
			"management.port":   strconv.Itoa(cfg.EurekaInstancePort),
			"prometheus.scrape": "true",
			"prometheus.path":   "/metrics",
		},
	}
	return c.request(http.MethodPost, "/apps/"+cfg.EurekaAppName, instance)
}

func (c *Client) StartHeartbeat(cfg *config.AppConfig) {
	ticker := time.NewTicker(30 * time.Second)
	go func() {
		defer ticker.Stop()
		for {
			select {
			case <-c.stopCh:
				return
			case <-ticker.C:
				if err := c.request(http.MethodPut, instancePath(cfg), nil); err != nil {
					slog.Warn("eureka heartbeat failed", "err", err)
					if registerErr := c.Register(cfg); registerErr != nil {
						slog.Warn("eureka re-registration failed", "err", registerErr)
					}
				}
			}
		}
	}()
}

func (c *Client) Deregister(cfg *config.AppConfig) error {
	close(c.stopCh)
	return c.request(http.MethodDelete, instancePath(cfg), nil)
}

// Keep Eureka's JSON model, but use a transport that verifies TLS and sends header credentials.
func (c *Client) request(method, path string, instance any) error {
	var body []byte
	if instance != nil {
		var err error
		body, err = json.Marshal(map[string]any{"instance": instance})
		if err != nil {
			return fmt.Errorf("encode Eureka registration")
		}
	}
	req, err := http.NewRequest(method, c.serverURL+path, bytes.NewReader(body))
	if err != nil {
		return fmt.Errorf("invalid Eureka URL")
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json")
	resp, err := c.client.Do(req)
	if err != nil {
		return fmt.Errorf("eureka transport failed: %w", err)
	}
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return fmt.Errorf("eureka returned HTTP %d", resp.StatusCode)
	}
	return nil
}

func instancePath(cfg *config.AppConfig) string {
	return "/apps/" + cfg.EurekaAppName + "/" + url.PathEscape(cfg.EurekaInstanceID)
}
