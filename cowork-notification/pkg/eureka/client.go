package eureka

import (
	"log/slog"
	"strconv"
	"time"

	eurekaclient "github.com/ArthurHlt/go-eureka-client/eureka"

	"github.com/cowork/cowork-notification/internal/config"
)

type Client struct {
	inner  *eurekaclient.Client
	stopCh chan struct{}
}

func New(cfg *config.AppConfig) *Client {
	return &Client{
		inner:  eurekaclient.NewClient([]string{cfg.EurekaServerURL}),
		stopCh: make(chan struct{}),
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
	instance.Metadata = &eurekaclient.MetaData{
		Map: map[string]string{
			"startup":           time.Now().String(),
			"management.port":   strconv.Itoa(cfg.EurekaInstancePort),
			"prometheus.scrape": "true",
			"prometheus.path":   "/metrics",
		},
	}
	return c.inner.RegisterInstance(cfg.EurekaAppName, instance)
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
				if err := c.inner.SendHeartbeat(cfg.EurekaAppName, cfg.EurekaInstanceHost); err != nil {
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
	return c.inner.UnregisterInstance(cfg.EurekaAppName, cfg.EurekaInstanceHost)
}
