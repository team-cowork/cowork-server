package eureka

import (
	"log/slog"
	"time"

	eurekaclient "github.com/ArthurHlt/go-eureka-client/eureka"

	"github.com/cowork/cowork-notification/internal/config"
)

type Client struct {
	inner *eurekaclient.Client
}

func New(cfg *config.AppConfig) *Client {
	return &Client{
		inner: eurekaclient.NewClient([]string{cfg.EurekaServerURL}),
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
		Map: map[string]string{"startup": time.Now().String()},
	}
	return c.inner.RegisterInstance(cfg.EurekaAppName, instance)
}

func (c *Client) StartHeartbeat(cfg *config.AppConfig) {
	ticker := time.NewTicker(30 * time.Second)
	go func() {
		for range ticker.C {
			if err := c.inner.SendHeartbeat(cfg.EurekaAppName, cfg.EurekaInstanceHost); err != nil {
				slog.Warn("eureka heartbeat failed", "err", err)
			}
		}
	}()
}

func (c *Client) Deregister(cfg *config.AppConfig) error {
	return c.inner.UnregisterInstance(cfg.EurekaAppName, cfg.EurekaInstanceHost)
}
