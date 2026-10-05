package eureka

import (
	"bytes"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"

	eureka "github.com/ArthurHlt/go-eureka-client/eureka"
	"github.com/cowork/authorization/internal/config"
	"github.com/cowork/authorization/internal/controlplane"
)

type Client struct {
	client    *http.Client
	serverURL string
	stopCh    chan struct{}
	stopOnce  sync.Once
}

func NewClient(cfg *config.AppConfig) *Client {
	client := controlplane.NewClient()
	return &Client{
		client:    client,
		serverURL: strings.TrimRight(cfg.EurekaServerURL, "/"),
		stopCh:    make(chan struct{}),
	}
}

func (c *Client) Register(cfg *config.AppConfig) error {
	instance := eureka.NewInstanceInfo(
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
	instance.Metadata = &eureka.MetaData{
		Map: map[string]string{
			"management.port":   fmt.Sprintf("%d", cfg.EurekaInstancePort),
			"prometheus.scrape": "true",
			"prometheus.path":   "/metrics",
		},
	}

	if err := c.request(http.MethodPost, "/apps/"+cfg.EurekaAppName, instance); err != nil {
		return fmt.Errorf("failed to register with eureka: %w", err)
	}

	log.Printf("registered with eureka as %s at %s:%d",
		cfg.EurekaAppName, cfg.EurekaInstanceHost, cfg.EurekaInstancePort)
	return nil
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
					log.Printf("eureka heartbeat failed: %v", err)
					if registerErr := c.Register(cfg); registerErr != nil {
						log.Printf("eureka re-registration failed: %v", registerErr)
					}
				}
			}
		}
	}()
}

func (c *Client) Deregister(cfg *config.AppConfig) {
	c.stopOnce.Do(func() {
		close(c.stopCh)
	})
	if err := c.request(http.MethodDelete, instancePath(cfg), nil); err != nil {
		log.Printf("failed to deregister from eureka: %v", err)
	}
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
