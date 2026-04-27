package eureka

import (
	"fmt"
	"log"
	"sync"
	"time"

	eureka "github.com/ArthurHlt/go-eureka-client/eureka"
	"github.com/cowork/authorization/internal/config"
)

type Client struct {
	client   *eureka.Client
	stopCh   chan struct{}
	stopOnce sync.Once
}

func NewClient(cfg *config.AppConfig) *Client {
	client := eureka.NewClient([]string{cfg.EurekaServerURL})
	return &Client{
		client: client,
		stopCh: make(chan struct{}),
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
	instance.Metadata = &eureka.MetaData{
		Map: map[string]string{
			"management.port":   fmt.Sprintf("%d", cfg.EurekaInstancePort),
			"prometheus.scrape": "true",
			"prometheus.path":   "/metrics",
		},
	}

	if err := c.client.RegisterInstance(cfg.EurekaAppName, instance); err != nil {
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
				if err := c.client.SendHeartbeat(cfg.EurekaAppName, cfg.EurekaInstanceHost); err != nil {
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
	if err := c.client.UnregisterInstance(cfg.EurekaAppName, cfg.EurekaInstanceHost); err != nil {
		log.Printf("failed to deregister from eureka: %v", err)
	}
}
