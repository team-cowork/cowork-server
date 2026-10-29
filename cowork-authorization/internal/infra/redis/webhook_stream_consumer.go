package redisinfra

import (
	"context"
	"errors"
	"log"
	"strconv"
	"strings"
	"time"

	"github.com/cowork/authorization/internal/domain"
	"github.com/cowork/authorization/internal/monitoring"
	redis "github.com/redis/go-redis/v9"
)

const (
	webhookStream       = "cowork:authorization:datagsm:webhooks"
	webhookGroup        = "cowork-authorization"
	webhookConsumer     = "authorization"
	maxWebhookBodyBytes = 1 << 20
	claimInterval       = 30 * time.Second
)

type WebhookProcessor interface {
	SecretConfigured() bool
	VerifySignature([]byte, string) bool
	ProcessEvent(context.Context, []byte) (domain.WebhookResult, error)
}

type WebhookStreamConsumer struct {
	client    *redis.Client
	processor WebhookProcessor
	cursor    string
}

func NewWebhookStreamConsumer(address string, processor WebhookProcessor) *WebhookStreamConsumer {
	return &WebhookStreamConsumer{
		client: redis.NewClient(&redis.Options{
			Addr: address, DialTimeout: 2 * time.Second, ReadTimeout: 5 * time.Second,
			WriteTimeout: 5 * time.Second, PoolSize: 2,
		}),
		processor: processor,
		cursor:    "0-0",
	}
}

// Run keeps the HTTP service available when Redis is down; Gateway can then
// fall back to Authorization's existing direct durable webhook endpoint.
func (c *WebhookStreamConsumer) Run(ctx context.Context) {
	defer c.client.Close()
	if !c.processor.SecretConfigured() {
		log.Println("DataGSM webhook stream consumer disabled: secret not configured")
		return
	}
	delay := 2 * time.Second
	for ctx.Err() == nil {
		if err := c.initGroup(ctx); err == nil {
			c.cursor = "0-0"
			monitoring.WebhookStreamConnected(true)
			delay = 2 * time.Second
			err = c.consume(ctx)
			if err == nil || ctx.Err() != nil {
				return
			}
		}
		monitoring.WebhookStreamConnected(false)
		monitoring.RecordWebhookStreamFailure("redis")
		log.Println("DataGSM webhook stream unavailable; retrying")
		if !wait(ctx, delay) {
			return
		}
		delay = min(delay*2, 30*time.Second)
	}
}

func (c *WebhookStreamConsumer) initGroup(ctx context.Context) error {
	if err := c.client.Ping(ctx).Err(); err != nil {
		return err
	}
	err := c.client.XGroupCreateMkStream(ctx, webhookStream, webhookGroup, "0").Err()
	if err != nil && !strings.Contains(err.Error(), "BUSYGROUP") {
		return err
	}
	return nil
}

func (c *WebhookStreamConsumer) consume(ctx context.Context) error {
	morePending, err := c.recoverPending(ctx, 0)
	if err != nil {
		return err
	}
	if err := c.observe(ctx); err != nil {
		return err
	}
	lastClaim := time.Now()
	lastObserve := time.Now()
	claimIdle := time.Duration(0)
	for ctx.Err() == nil {
		block := 2 * time.Second
		if morePending {
			block = 100 * time.Millisecond
		}
		streams, err := c.client.XReadGroup(ctx, &redis.XReadGroupArgs{
			Group: webhookGroup, Consumer: webhookConsumer,
			Streams: []string{webhookStream, ">"}, Count: 10, Block: block,
		}).Result()
		if err != nil && !errors.Is(err, redis.Nil) {
			return err
		}
		for _, stream := range streams {
			for _, message := range stream.Messages {
				c.process(ctx, message)
			}
		}
		if morePending || time.Since(lastClaim) >= claimInterval {
			if !morePending {
				claimIdle = claimInterval
			}
			morePending, err = c.recoverPending(ctx, claimIdle)
			if err != nil {
				return err
			}
			if !morePending {
				lastClaim = time.Now()
			}
		}
		if time.Since(lastObserve) >= claimInterval {
			if err := c.observe(ctx); err != nil {
				return err
			}
			lastObserve = time.Now()
		}
	}
	return nil
}

func (c *WebhookStreamConsumer) recoverPending(ctx context.Context, minIdle time.Duration) (bool, error) {
	for range 10 {
		messages, next, err := c.client.XAutoClaim(ctx, &redis.XAutoClaimArgs{
			Stream: webhookStream, Group: webhookGroup, Consumer: webhookConsumer,
			MinIdle: minIdle, Start: c.cursor, Count: 50,
		}).Result()
		if err != nil {
			return false, err
		}
		for _, message := range messages {
			c.process(ctx, message)
		}
		c.cursor = next
		if next == "0-0" {
			return false, nil
		}
	}
	return true, nil
}

func (c *WebhookStreamConsumer) process(ctx context.Context, message redis.XMessage) {
	result, err := processQueuedWebhook(ctx, c.processor, message, c.ackAndDelete)
	if err != nil {
		monitoring.RecordWebhookStreamFailure(streamErrorKind(err))
		log.Println("DataGSM webhook stream entry remains pending")
		return
	}
	monitoring.RecordWebhookStreamProcessed(string(result))
}

func processQueuedWebhook(ctx context.Context, processor WebhookProcessor, message redis.XMessage,
	ack func(context.Context, string) error) (domain.WebhookResult, error) {
	body, bodyOK := message.Values["body"].(string)
	signature, signatureOK := message.Values["signature"].(string)
	if !bodyOK || !signatureOK || len(body) == 0 || len(body) > maxWebhookBodyBytes ||
		!processor.VerifySignature([]byte(body), signature) {
		return "", domain.ErrInvalidWebhookPayload
	}
	result, err := processor.ProcessEvent(ctx, []byte(body))
	if err != nil {
		return "", err
	}
	if err := ack(ctx, message.ID); err != nil {
		return "", err
	}
	return result, nil
}

func (c *WebhookStreamConsumer) ackAndDelete(ctx context.Context, id string) error {
	_, err := c.client.TxPipelined(ctx, func(pipe redis.Pipeliner) error {
		pipe.XAck(ctx, webhookStream, webhookGroup, id)
		pipe.XDel(ctx, webhookStream, id)
		return nil
	})
	return err
}

func (c *WebhookStreamConsumer) observe(ctx context.Context) error {
	length, err := c.client.XLen(ctx, webhookStream).Result()
	if err != nil {
		return err
	}
	pending, err := c.client.XPending(ctx, webhookStream, webhookGroup).Result()
	if err != nil {
		return err
	}
	oldestSeconds := 0.0
	if length > 0 {
		first, err := c.client.XRangeN(ctx, webhookStream, "-", "+", 1).Result()
		if err != nil {
			return err
		}
		if len(first) > 0 {
			millisText, _, _ := strings.Cut(first[0].ID, "-")
			millis, err := strconv.ParseInt(millisText, 10, 64)
			if err != nil {
				return err
			}
			oldestSeconds = max(0, time.Since(time.UnixMilli(millis)).Seconds())
		}
	}
	monitoring.ObserveWebhookStream(length, pending.Count, oldestSeconds)
	return nil
}

func streamErrorKind(err error) string {
	if errors.Is(err, domain.ErrInvalidWebhookPayload) || errors.Is(err, domain.ErrWebhookConflict) ||
		errors.Is(err, domain.ErrWebhookExpired) || errors.Is(err, domain.ErrWebhookFuture) {
		return "invalid"
	}
	return "processing"
}

func wait(ctx context.Context, delay time.Duration) bool {
	timer := time.NewTimer(delay)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return false
	case <-timer.C:
		return true
	}
}
