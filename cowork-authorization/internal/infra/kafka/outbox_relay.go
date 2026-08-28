package kafka

import (
	"context"
	"database/sql"
	"log"
	"time"
)

const (
	outboxLockName       = "cowork-authorization:kafka-outbox"
	outboxBatchSize      = 100
	outboxRelayInterval  = time.Second
	outboxPublishTimeout = 10 * time.Second
	outboxErrorMaxRunes  = 8000
)

type outboxPublisher interface {
	PublishTo(ctx context.Context, topic string, key string, value []byte) error
	PublishToPartition(ctx context.Context, topic string, partition int, key string, value []byte) error
}

type outboxRecord struct {
	id        int64
	topic     string
	key       string
	payload   []byte
	partition *int
}

// OutboxRelay publishes committed rows in id order. A crash after Kafka accepts a
// message but before the row is deleted can redeliver it, so delivery is explicitly
// at-least-once and consumers must remain idempotent.
type OutboxRelay struct {
	db        *sql.DB
	publisher outboxPublisher
}

func NewOutboxRelay(db *sql.DB, publisher outboxPublisher) *OutboxRelay {
	return &OutboxRelay{db: db, publisher: publisher}
}

func (r *OutboxRelay) Run(ctx context.Context) {
	r.relayOnce(ctx)

	ticker := time.NewTicker(outboxRelayInterval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			r.relayOnce(ctx)
		}
	}
}

func (r *OutboxRelay) relayOnce(ctx context.Context) {
	conn, err := r.db.Conn(ctx)
	if err != nil {
		if ctx.Err() == nil {
			log.Printf("failed to acquire authorization outbox connection: %v", err)
		}
		return
	}
	defer func() { _ = conn.Close() }()

	locked, err := acquireOutboxLock(ctx, conn)
	if err != nil {
		if ctx.Err() == nil {
			log.Printf("failed to acquire authorization outbox lock: %v", err)
		}
		return
	}
	if !locked {
		return
	}
	defer releaseOutboxLock(conn)

	// Locking read is intentional: an ordinary consistent read can skip an
	// uncommitted lower auto-increment id and publish a later snapshot marker
	// first. The next-key lock makes the marker wait for the older producer tx.
	tx, err := conn.BeginTx(ctx, nil)
	if err != nil {
		if ctx.Err() == nil {
			log.Printf("failed to begin authorization outbox relay transaction: %v", err)
		}
		return
	}
	defer func() { _ = tx.Rollback() }()

	records, err := loadOutboxBatch(ctx, tx)
	if err != nil {
		if ctx.Err() == nil {
			log.Printf("failed to load authorization outbox: %v", err)
		}
		return
	}

	for _, record := range records {
		publishCtx, cancel := context.WithTimeout(ctx, outboxPublishTimeout)
		var err error
		if record.partition == nil {
			err = r.publisher.PublishTo(publishCtx, record.topic, record.key, record.payload)
		} else {
			err = r.publisher.PublishToPartition(
				publishCtx,
				record.topic,
				*record.partition,
				record.key,
				record.payload,
			)
		}
		cancel()
		if err != nil {
			if ctx.Err() == nil {
				markOutboxFailure(ctx, tx, record.id, err)
				log.Printf("failed to publish authorization outbox row %d to %s: %v", record.id, record.topic, err)
			}
			if commitErr := tx.Commit(); commitErr != nil && ctx.Err() == nil {
				log.Printf("failed to commit authorization outbox failure state: %v", commitErr)
			}
			return
		}

		if _, err := tx.ExecContext(ctx, "DELETE FROM tb_kafka_outbox WHERE id = ?", record.id); err != nil {
			if ctx.Err() == nil {
				log.Printf("failed to delete published authorization outbox row %d: %v", record.id, err)
			}
			return
		}
	}
	if err := tx.Commit(); err != nil && ctx.Err() == nil {
		log.Printf("failed to commit authorization outbox relay transaction: %v", err)
	}
}

func acquireOutboxLock(ctx context.Context, conn *sql.Conn) (bool, error) {
	var acquired sql.NullInt64
	if err := conn.QueryRowContext(ctx, "SELECT GET_LOCK(?, 0)", outboxLockName).Scan(&acquired); err != nil {
		return false, err
	}
	return acquired.Valid && acquired.Int64 == 1, nil
}

func releaseOutboxLock(conn *sql.Conn) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	var released sql.NullInt64
	if err := conn.QueryRowContext(ctx, "SELECT RELEASE_LOCK(?)", outboxLockName).Scan(&released); err != nil {
		log.Printf("failed to release authorization outbox lock: %v", err)
		return
	}
	if !released.Valid || released.Int64 != 1 {
		log.Printf("authorization outbox lock was not held while releasing it")
	}
}

func loadOutboxBatch(ctx context.Context, tx *sql.Tx) ([]outboxRecord, error) {
	rows, err := tx.QueryContext(
		ctx,
		`SELECT id, topic, partition_id, event_key, payload
		 FROM tb_kafka_outbox
		 ORDER BY id ASC
		 LIMIT ?
		 FOR UPDATE`,
		outboxBatchSize,
	)
	if err != nil {
		return nil, err
	}
	defer func() { _ = rows.Close() }()

	records := make([]outboxRecord, 0, outboxBatchSize)
	for rows.Next() {
		var record outboxRecord
		var partition sql.NullInt64
		if err := rows.Scan(&record.id, &record.topic, &partition, &record.key, &record.payload); err != nil {
			return nil, err
		}
		if partition.Valid {
			value := int(partition.Int64)
			record.partition = &value
		}
		records = append(records, record)
	}
	return records, rows.Err()
}

func markOutboxFailure(ctx context.Context, tx *sql.Tx, id int64, publishErr error) {
	message := []rune(publishErr.Error())
	if len(message) > outboxErrorMaxRunes {
		message = message[:outboxErrorMaxRunes]
	}
	if _, err := tx.ExecContext(
		ctx,
		"UPDATE tb_kafka_outbox SET attempts = attempts + 1, last_error = ? WHERE id = ?",
		string(message),
		id,
	); err != nil {
		log.Printf("failed to record authorization outbox row %d failure: %v", id, err)
	}
}
