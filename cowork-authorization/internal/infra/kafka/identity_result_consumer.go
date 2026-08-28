package kafka

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"strings"
	"time"

	"github.com/cowork/authorization/internal/domain"
	"github.com/segmentio/kafka-go"
)

type identityResultProcessor interface {
	ApplyResult(context.Context, string, domain.UserIdentityCommandResult) error
}

type identityResultDeadLetterPublisher interface {
	PublishToWithHeaders(context.Context, string, string, []byte, []kafka.Header) error
}

type IdentityResultConsumer struct {
	reader    *kafka.Reader
	processor identityResultProcessor
	dltTopic  string
	publisher identityResultDeadLetterPublisher
}

func NewIdentityResultConsumer(
	bootstrapServers string,
	topic string,
	groupID string,
	dltTopic string,
	processor identityResultProcessor,
	publisher identityResultDeadLetterPublisher,
) *IdentityResultConsumer {
	return &IdentityResultConsumer{
		reader: kafka.NewReader(kafka.ReaderConfig{
			Brokers:        strings.Split(bootstrapServers, ","),
			Topic:          topic,
			GroupID:        groupID,
			MinBytes:       1,
			MaxBytes:       10e6,
			CommitInterval: 0,
		}),
		processor: processor,
		dltTopic:  dltTopic,
		publisher: publisher,
	}
}

func (c *IdentityResultConsumer) Run(ctx context.Context) {
	for {
		message, err := c.reader.FetchMessage(ctx)
		if err != nil {
			if ctx.Err() != nil {
				return
			}
			log.Printf("failed to fetch user identity command result: %v", err)
			time.Sleep(time.Second)
			continue
		}

		for ctx.Err() == nil {
			result, processErr := decodeIdentityResult(message.Value)
			permanent := processErr != nil
			if !permanent && string(message.Key) != result.OperationID {
				processErr = fmt.Errorf("result key does not match operationId")
				permanent = true
			}
			if processErr == nil {
				processErr = c.processor.ApplyResult(ctx, string(message.Key), result)
				permanent = errors.Is(processErr, domain.ErrIdentityResultRejected)
			}

			if permanent {
				if err := c.deadLetter(ctx, message, processErr); err != nil {
					log.Printf(
						"failed to dead-letter user identity result topic=%s partition=%d offset=%d: %v",
						message.Topic,
						message.Partition,
						message.Offset,
						err,
					)
					time.Sleep(time.Second)
					continue
				}
				c.commit(ctx, message)
				break
			}
			if processErr != nil {
				// Storage failures are transient: keep retrying this exact record.
				log.Printf(
					"user identity command result persistence failed topic=%s partition=%d offset=%d: %v",
					message.Topic,
					message.Partition,
					message.Offset,
					processErr,
				)
				time.Sleep(time.Second)
				continue
			}
			c.commit(ctx, message)
			break
		}
	}
}

func (c *IdentityResultConsumer) deadLetter(
	ctx context.Context,
	message kafka.Message,
	reason error,
) error {
	headers := []kafka.Header{
		{Key: "cowork-dlt-original-topic", Value: []byte(message.Topic)},
		{Key: "cowork-dlt-original-partition", Value: []byte(fmt.Sprintf("%d", message.Partition))},
		{Key: "cowork-dlt-original-offset", Value: []byte(fmt.Sprintf("%d", message.Offset))},
		{Key: "cowork-dlt-reason", Value: []byte(truncateDLTReason(reason.Error()))},
	}
	if err := c.publisher.PublishToWithHeaders(
		ctx,
		c.dltTopic,
		string(message.Key),
		message.Value,
		headers,
	); err != nil {
		return err
	}
	log.Printf(
		"dead-lettered invalid user identity result topic=%s partition=%d offset=%d reason=%v",
		message.Topic,
		message.Partition,
		message.Offset,
		reason,
	)
	return nil
}

func (c *IdentityResultConsumer) commit(ctx context.Context, message kafka.Message) {
	for ctx.Err() == nil {
		if err := c.reader.CommitMessages(ctx, message); err != nil {
			log.Printf("failed to commit user identity command result: %v", err)
			time.Sleep(time.Second)
			continue
		}
		return
	}
}

func truncateDLTReason(reason string) string {
	const maxBytes = 500
	if len(reason) <= maxBytes {
		return reason
	}
	return reason[:maxBytes]
}

func (c *IdentityResultConsumer) Close() error {
	return c.reader.Close()
}

func decodeIdentityResult(payload []byte) (domain.UserIdentityCommandResult, error) {
	var result domain.UserIdentityCommandResult
	decoder := json.NewDecoder(bytes.NewReader(payload))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&result); err != nil {
		return result, err
	}
	if err := decoder.Decode(&struct{}{}); err != io.EOF {
		if err == nil {
			return result, fmt.Errorf("result contains trailing JSON")
		}
		return result, err
	}
	return result, domain.ValidateUserIdentityCommandResult(result)
}
