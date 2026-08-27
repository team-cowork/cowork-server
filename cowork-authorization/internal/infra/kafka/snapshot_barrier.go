package kafka

import (
	"context"
	"crypto/rand"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"log"
	"sort"
	"time"

	"github.com/cowork/authorization/internal/domain"
)

const (
	snapshotBarrierKeyPrefix = "__cowork_projection_snapshot_complete__:"
	snapshotBarrierEventType = "PROJECTION_SNAPSHOT_COMPLETED"
	snapshotBarrierSource    = "cowork-authorization"
	snapshotBarrierInterval  = 5 * time.Minute
	snapshotBarrierRetry     = 5 * time.Second
)

type partitionDiscoverer interface {
	Partitions(ctx context.Context, topic string) ([]int, error)
}

type snapshotBarrierEvent struct {
	EventType  string    `json:"eventType"`
	Topic      string    `json:"topic"`
	Partition  int       `json:"partition"`
	SnapshotID string    `json:"snapshotId"`
	OccurredAt time.Time `json:"occurredAt"`
	Source     string    `json:"source"`
}

type snapshotOutboxRow struct {
	topic     string
	partition *int
	key       string
	payload   []byte
}

// SnapshotBarrierPublisher appends every durable presence row before one marker
// per partition. Consumers stay fail-closed until every marker is applied.
type SnapshotBarrierPublisher struct {
	db       *sql.DB
	topic    string
	metadata partitionDiscoverer
}

func NewSnapshotBarrierPublisher(
	db *sql.DB,
	topic string,
	metadata partitionDiscoverer,
) *SnapshotBarrierPublisher {
	return &SnapshotBarrierPublisher{db: db, topic: topic, metadata: metadata}
}

func (p *SnapshotBarrierPublisher) Run(ctx context.Context) {
	delay := time.Duration(0)
	for {
		if !waitForBarrierRun(ctx, delay) {
			return
		}
		if err := p.enqueue(ctx, time.Now().UTC().Truncate(time.Microsecond)); err != nil {
			if ctx.Err() != nil {
				return
			}
			log.Printf("failed to enqueue authorization projection snapshot marker: %v", err)
			delay = snapshotBarrierRetry
			continue
		}
		delay = snapshotBarrierInterval
	}
}

func (p *SnapshotBarrierPublisher) enqueue(ctx context.Context, occurredAt time.Time) error {
	partitions, err := p.metadata.Partitions(ctx, p.topic)
	if err != nil {
		return fmt.Errorf("load topic partitions: %w", err)
	}
	partitions, err = normalizeSnapshotPartitions(partitions)
	if err != nil {
		return err
	}
	snapshotID, err := newSnapshotID()
	if err != nil {
		return fmt.Errorf("create snapshot id: %w", err)
	}

	tx, err := p.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback() }()

	states, err := loadPresenceSnapshotStates(ctx, tx)
	if err != nil {
		return err
	}

	outboxRows, err := buildPresenceSnapshotRows(
		p.topic,
		states,
		partitions,
		snapshotID,
		occurredAt,
	)
	if err != nil {
		return err
	}

	for _, row := range outboxRows {
		if _, err := tx.ExecContext(
			ctx,
			`INSERT INTO tb_kafka_outbox (topic, partition_id, event_key, payload)
			 VALUES (?, ?, ?, ?)`,
			row.topic,
			row.partition,
			row.key,
			row.payload,
		); err != nil {
			return err
		}
	}
	return tx.Commit()
}

func normalizeSnapshotPartitions(partitions []int) ([]int, error) {
	if len(partitions) == 0 {
		return nil, fmt.Errorf("topic has no partitions")
	}
	result := append([]int(nil), partitions...)
	sort.Ints(result)
	for index, partition := range result {
		if partition < 0 {
			return nil, fmt.Errorf("invalid topic partition %d", partition)
		}
		if index > 0 && result[index-1] == partition {
			return nil, fmt.Errorf("duplicate topic partition %d", partition)
		}
	}
	return result, nil
}

func loadPresenceSnapshotStates(
	ctx context.Context,
	tx *sql.Tx,
) ([]domain.UserPresenceState, error) {
	// Session transitions lock the same source rows. Holding these locks through
	// state-event and marker insertion linearizes the snapshot with mutations.
	rows, err := tx.QueryContext(
		ctx,
		`SELECT user_id, status, active_session_count, occurred_at
		 FROM tb_user_presence_states
		 ORDER BY user_id
		 FOR UPDATE`,
	)
	if err != nil {
		return nil, err
	}
	defer func() { _ = rows.Close() }()

	states := make([]domain.UserPresenceState, 0)
	for rows.Next() {
		var state domain.UserPresenceState
		if err := rows.Scan(
			&state.UserID,
			&state.Status,
			&state.ActiveSessionCount,
			&state.OccurredAt,
		); err != nil {
			return nil, err
		}
		state.OccurredAt = state.OccurredAt.UTC().Truncate(time.Microsecond)
		states = append(states, state)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	return states, nil
}

func buildPresenceSnapshotRows(
	topic string,
	states []domain.UserPresenceState,
	partitions []int,
	snapshotID string,
	markerOccurredAt time.Time,
) ([]snapshotOutboxRow, error) {
	rows := make([]snapshotOutboxRow, 0, len(states)+len(partitions))

	for _, state := range states {
		payload, err := json.Marshal(domain.UserPresenceEvent{
			EventType:  "STATUS_CHANGED",
			UserID:     state.UserID,
			Status:     state.Status,
			OccurredAt: state.OccurredAt.UTC().Truncate(time.Microsecond),
		})
		if err != nil {
			return nil, err
		}
		rows = append(rows, snapshotOutboxRow{
			topic:   topic,
			key:     fmt.Sprintf("%d", state.UserID),
			payload: payload,
		})
	}

	for _, partition := range partitions {
		event := snapshotBarrierEvent{
			EventType:  snapshotBarrierEventType,
			Topic:      topic,
			Partition:  partition,
			SnapshotID: snapshotID,
			OccurredAt: markerOccurredAt.UTC().Truncate(time.Microsecond),
			Source:     snapshotBarrierSource,
		}
		payload, err := json.Marshal(event)
		if err != nil {
			return nil, err
		}
		partitionID := partition
		rows = append(rows, snapshotOutboxRow{
			topic:     topic,
			partition: &partitionID,
			key:       fmt.Sprintf("%s%d", snapshotBarrierKeyPrefix, partition),
			payload:   payload,
		})
	}
	return rows, nil
}

func waitForBarrierRun(ctx context.Context, delay time.Duration) bool {
	if delay == 0 {
		return true
	}
	timer := time.NewTimer(delay)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return false
	case <-timer.C:
		return true
	}
}

func newSnapshotID() (string, error) {
	value := make([]byte, 16)
	if _, err := rand.Read(value); err != nil {
		return "", err
	}
	value[6] = (value[6] & 0x0f) | 0x40
	value[8] = (value[8] & 0x3f) | 0x80
	encoded := hex.EncodeToString(value)
	return fmt.Sprintf(
		"%s-%s-%s-%s-%s",
		encoded[0:8],
		encoded[8:12],
		encoded[12:16],
		encoded[16:20],
		encoded[20:32],
	), nil
}
