package repository

import (
	"bytes"
	"context"
	"database/sql"
	"errors"
	"fmt"
	"log"
	"strings"
	"time"

	"github.com/cowork/authorization/internal/domain"
	"github.com/cowork/authorization/internal/monitoring"
	mysqlDriver "github.com/go-sql-driver/mysql"
)

const (
	webhookTransactionTimeout = 10 * time.Second
	webhookCleanupBatchSize   = 500
	webhookOutboxBatchSize    = 100
)

type WebhookInboxRepository struct{ db *sql.DB }

func NewWebhookInboxRepository(db *sql.DB) *WebhookInboxRepository {
	return &WebhookInboxRepository{db: db}
}

// SubmitBatch claims an identity and writes the complete batch in one local
// transaction. SQL logging cannot expose the signed payload or identity values.
func (r *WebhookInboxRepository) SubmitBatch(ctx context.Context, batch domain.WebhookBatch, topic string) (domain.WebhookResult, error) {
	ctx, cancel := context.WithTimeout(ctx, webhookTransactionTimeout)
	defer cancel()
	if strings.TrimSpace(topic) == "" {
		return "", errors.New("webhook outbox topic is not configured")
	}
	tx, err := r.db.BeginTx(ctx, nil)
	if err != nil {
		return "", err
	}
	defer func() { _ = tx.Rollback() }()

	var nowText string
	if err := tx.QueryRowContext(ctx, `SELECT DATE_FORMAT(UTC_TIMESTAMP(6), '%Y-%m-%d %H:%i:%s.%f')`).Scan(&nowText); err != nil {
		return "", err
	}
	now, err := time.Parse("2006-01-02 15:04:05.000000", nowText)
	if err != nil {
		return "", err
	}
	if err := domain.ValidateWebhookWindow(batch.OccurredAt, now); err != nil {
		return "", err
	}

	_, err = tx.ExecContext(ctx, `INSERT INTO tb_webhook_inbox
		(event_id, event_type, payload_hash, message_count, occurred_at, accepted_at, expires_at)
		VALUES (?, ?, ?, ?, ?, ?, ?)`,
		[]byte(batch.EventID), batch.EventType, batch.PayloadHash[:], len(batch.Messages),
		webhookSQLTime(batch.OccurredAt), webhookSQLTime(now), webhookSQLTime(domain.WebhookExpiresAt(now, batch.OccurredAt)))
	if err != nil {
		var duplicate *mysqlDriver.MySQLError
		if !errors.As(err, &duplicate) || duplicate.Number != 1062 {
			return "", err
		}
		var hash []byte
		if err := tx.QueryRowContext(ctx, `SELECT payload_hash FROM tb_webhook_inbox WHERE event_id = ? FOR UPDATE`, []byte(batch.EventID)).Scan(&hash); err != nil {
			return "", err
		}
		if !bytes.Equal(hash, batch.PayloadHash[:]) {
			return "", domain.ErrWebhookConflict
		}
		if err := tx.Commit(); err != nil {
			return "", err
		}
		return domain.WebhookDuplicate, nil
	}

	for start := 0; start < len(batch.Messages); start += webhookOutboxBatchSize {
		end := min(start+webhookOutboxBatchSize, len(batch.Messages))
		args := make([]any, 0, (end-start)*5)
		for _, message := range batch.Messages[start:end] {
			args = append(args, topic, message.Key, string(message.Payload), []byte(batch.EventID), message.Index)
		}
		// Only placeholders are assembled; every provider value is a SQL argument.
		query := `INSERT INTO tb_kafka_outbox (topic, event_key, payload, source_event_id, source_event_index, created_at) VALUES ` +
			strings.TrimSuffix(strings.Repeat("(?, ?, ?, ?, ?, UTC_TIMESTAMP(6)),", end-start), ",")
		if _, err := tx.ExecContext(ctx, query, args...); err != nil {
			return "", err
		}
	}
	if err := tx.Commit(); err != nil {
		return "", err
	}
	return domain.WebhookAccepted, nil
}

// CleanupExpired never removes a receipt referenced by a pending outbox row.
// The FK is a second guard against a concurrent or accidental unsafe deletion.
func (r *WebhookInboxRepository) CleanupExpired(ctx context.Context) error {
	ctx, cancel := context.WithTimeout(ctx, webhookTransactionTimeout)
	defer cancel()
	// Bound the work per sweep as well as the size of each delete.
	for range 20 {
		result, err := r.db.ExecContext(ctx, `DELETE FROM tb_webhook_inbox
			WHERE expires_at <= UTC_TIMESTAMP(6)
			AND NOT EXISTS (SELECT 1 FROM tb_kafka_outbox WHERE source_event_id = tb_webhook_inbox.event_id)
			ORDER BY expires_at, event_id LIMIT ?`, webhookCleanupBatchSize)
		if err != nil {
			return err
		}
		deleted, err := result.RowsAffected()
		if err != nil {
			return err
		}
		monitoring.RecordWebhookCleanup(deleted)
		if deleted < webhookCleanupBatchSize {
			return nil
		}
	}
	return nil
}

// RunMaintenance owns bounded, cancellable cleanup and shared-dataset gauges.
func (r *WebhookInboxRepository) RunMaintenance(ctx context.Context) {
	cleanup := func() {
		if err := r.CleanupExpired(ctx); err != nil && ctx.Err() == nil {
			monitoring.RecordWebhookMaintenanceFailure("cleanup")
			log.Println("Failed to clean expired webhook receipts")
		}
	}
	observe := func() {
		if err := r.refreshMetrics(ctx); err != nil && ctx.Err() == nil {
			monitoring.RecordWebhookMaintenanceFailure("observe")
			log.Println("Failed to observe webhook backlog")
		}
	}
	cleanup()
	observe()
	cleanupTicker := time.NewTicker(time.Hour)
	metricsTicker := time.NewTicker(time.Minute)
	defer cleanupTicker.Stop()
	defer metricsTicker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-cleanupTicker.C:
			cleanup()
		case <-metricsTicker.C:
			observe()
		}
	}
}

func (r *WebhookInboxRepository) refreshMetrics(ctx context.Context) error {
	ctx, cancel := context.WithTimeout(ctx, webhookTransactionTimeout)
	defer cancel()
	var pending, retained, expired int64
	var oldest float64
	if err := r.db.QueryRowContext(ctx, `SELECT COUNT(*),
		COALESCE(MAX(GREATEST(TIMESTAMPDIFF(MICROSECOND, created_at, UTC_TIMESTAMP(6)), 0)) / 1000000.0, 0)
		FROM tb_kafka_outbox WHERE source_event_id IS NOT NULL`).Scan(&pending, &oldest); err != nil {
		monitoring.WebhookObservationFailed()
		return fmt.Errorf("observe pending webhook outbox: %w", err)
	}
	if err := r.db.QueryRowContext(ctx, `SELECT COUNT(*),
		COALESCE(SUM(EXISTS(SELECT 1 FROM tb_kafka_outbox WHERE source_event_id = tb_webhook_inbox.event_id)), 0)
		FROM tb_webhook_inbox WHERE expires_at <= UTC_TIMESTAMP(6)`).Scan(&expired, &retained); err != nil {
		monitoring.WebhookObservationFailed()
		return fmt.Errorf("observe expired webhook inbox: %w", err)
	}
	monitoring.ObserveWebhookBacklog(pending, oldest, expired, retained)
	return nil
}

func webhookSQLTime(value time.Time) string {
	return value.UTC().Truncate(time.Microsecond).Format("2006-01-02 15:04:05.000000")
}
