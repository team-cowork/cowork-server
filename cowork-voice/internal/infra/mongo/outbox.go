package mongo

import (
	"context"
	"encoding/json"
	"time"

	"go.mongodb.org/mongo-driver/v2/bson"
	"go.mongodb.org/mongo-driver/v2/mongo"
	"go.mongodb.org/mongo-driver/v2/mongo/options"

	"github.com/cowork/cowork-voice/internal/apperr"
)

const CollectionOutbox = "voice_outbox"

// OutboxMessage는 transactional outbox 패턴의 메시지다.
// 도메인 서비스는 진실의 원천(Mongo)에 이벤트를 먼저 적재하고, relay가 Kafka로 전송한다.
type OutboxMessage struct {
	ID        bson.ObjectID `bson:"_id,omitempty"`
	Key       string        `bson:"key"`
	Payload   []byte        `bson:"payload"`
	CreatedAt time.Time     `bson:"created_at"`
	SentAt    *time.Time    `bson:"sent_at,omitempty"`
	// FailedAt이 설정되면 재시도 한도를 초과해 격리된(전송 포기) 메시지로, FetchUnsent에서 제외된다.
	// 큐 진행을 막지 않으면서 운영자가 사후 조회·재처리할 수 있도록 삭제하지 않고 보존한다.
	FailedAt *time.Time `bson:"failed_at,omitempty"`
	Attempts int        `bson:"attempts"`
}

type OutboxRepository struct {
	col *mongo.Collection
}

func NewOutboxRepository(db *mongo.Database) *OutboxRepository {
	return &OutboxRepository{col: db.Collection(CollectionOutbox)}
}

// Publish는 도메인의 EventPublisher 인터페이스를 구현한다.
// Kafka로 직접 보내지 않고 이벤트를 outbox에 내구성 있게 적재한다(Kafka 장애와 분리).
func (r *OutboxRepository) Publish(ctx context.Context, key string, v any) error {
	payload, err := json.Marshal(v)
	if err != nil {
		return apperr.Internal(err.Error())
	}
	_, err = r.col.InsertOne(ctx, OutboxMessage{
		Key:       key,
		Payload:   payload,
		CreatedAt: time.Now().UTC(),
	})
	if err != nil {
		return apperr.Internal(err.Error())
	}
	return nil
}

// FetchUnsent는 아직 전송되지 않은 메시지를 생성 순서대로 반환한다(relay 전용).
func (r *OutboxRepository) FetchUnsent(ctx context.Context, limit int) ([]OutboxMessage, error) {
	opts := options.Find().
		SetSort(bson.D{{Key: "created_at", Value: 1}}).
		SetLimit(int64(limit))
	filter := bson.D{{Key: "sent_at", Value: nil}, {Key: "failed_at", Value: nil}}
	cur, err := r.col.Find(ctx, filter, opts)
	if err != nil {
		return nil, err
	}
	defer func() { _ = cur.Close(ctx) }()

	var msgs []OutboxMessage
	if err := cur.All(ctx, &msgs); err != nil {
		return nil, err
	}
	return msgs, nil
}

func (r *OutboxRepository) MarkSent(ctx context.Context, id bson.ObjectID, sentAt time.Time) error {
	_, err := r.col.UpdateByID(ctx, id, bson.D{{Key: "$set", Value: bson.D{{Key: "sent_at", Value: sentAt}}}})
	return err
}

func (r *OutboxRepository) IncrementAttempts(ctx context.Context, id bson.ObjectID) error {
	_, err := r.col.UpdateByID(ctx, id, bson.D{{Key: "$inc", Value: bson.D{{Key: "attempts", Value: 1}}}})
	return err
}

// MarkFailed는 재시도 한도를 초과한 메시지를 격리한다(전송 포기). 이후 FetchUnsent에서 제외된다.
func (r *OutboxRepository) MarkFailed(ctx context.Context, id bson.ObjectID, failedAt time.Time) error {
	_, err := r.col.UpdateByID(ctx, id, bson.D{{Key: "$set", Value: bson.D{{Key: "failed_at", Value: failedAt}}}})
	return err
}
