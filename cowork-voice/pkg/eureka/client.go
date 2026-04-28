package eureka

import (
	"fmt"
	"log/slog"
	"sync"
	"time"

	eurekaclient "github.com/ArthurHlt/go-eureka-client/eureka"
)

type Client struct {
	inner    *eurekaclient.Client
	stopCh   chan struct{}
	stopOnce sync.Once
}

func New(serverURL string) *Client {
	return &Client{
		inner:  eurekaclient.NewClient([]string{serverURL}),
		stopCh: make(chan struct{}),
	}
}

func (c *Client) Register(appName, host string, port int) error {
	instance := eurekaclient.NewInstanceInfo(host, appName, host, port, 30, false)
	instance.VipAddress = appName
	instance.SecureVipAddress = appName
	instance.Metadata = &eurekaclient.MetaData{
		Map: map[string]string{"management.port": fmt.Sprintf("%d", port)},
	}
	if err := c.inner.RegisterInstance(appName, instance); err != nil {
		return fmt.Errorf("eureka register failed: %w", err)
	}
	slog.Info("registered with eureka", "app", appName, "host", host, "port", port)
	return nil
}

func (c *Client) StartHeartbeat(appName, host string, port int) {
	ticker := time.NewTicker(30 * time.Second)
	go func() {
		defer ticker.Stop()
		for {
			select {
			case <-c.stopCh:
				return
			case <-ticker.C:
				if err := c.inner.SendHeartbeat(appName, host); err != nil {
					slog.Warn("eureka heartbeat failed", "err", err)
					if err := c.Register(appName, host, port); err != nil {
						slog.Warn("eureka re-registration failed", "err", err)
					}
				}
			}
		}
	}()
}

func (c *Client) Deregister(appName, host string) {
	c.stopOnce.Do(func() { close(c.stopCh) })
	if err := c.inner.UnregisterInstance(appName, host); err != nil {
		slog.Warn("eureka deregister failed", "err", err)
	}
}
