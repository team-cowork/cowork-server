package mongo

import (
	"context"
	"encoding/json"
	"errors"
	"sort"
	"time"

	"go.mongodb.org/mongo-driver/v2/bson"
	"go.mongodb.org/mongo-driver/v2/mongo"
	"go.mongodb.org/mongo-driver/v2/mongo/options"

	livedomain "github.com/cowork/cowork-voice/internal/domain/live_room"
	roomdomain "github.com/cowork/cowork-voice/internal/domain/voice_room"
)

const outboxField = "outbox"

// OutboxMessage is embedded in the authoritative document changed by the same
// update. Source and OwnerID are relay-only routing metadata populated while
// reading and are not persisted inside the embedded event.
type OutboxMessage struct {
	ID        bson.ObjectID `bson:"_id"`
	Key       string        `bson:"key"`
	Payload   []byte        `bson:"payload"`
	CreatedAt time.Time     `bson:"created_at"`
	SentAt    *time.Time    `bson:"sent_at,omitempty"`
	FailedAt  *time.Time    `bson:"failed_at,omitempty"`
	Attempts  int           `bson:"attempts"`
	Source    string        `bson:"-"`
	OwnerID   bson.ObjectID `bson:"-"`
}

type OutboxRepository struct {
	db *mongo.Database
}

func NewOutboxRepository(db *mongo.Database) *OutboxRepository {
	return &OutboxRepository{db: db}
}

// newOutboxMessage assigns the durable event identity before the authoritative
// update. That same identity is included in the payload, so a publish followed
// by a crash before MarkSent can be deduplicated by consumers.
func newOutboxMessage(key string, value any, createdAt time.Time) (OutboxMessage, error) {
	id := bson.NewObjectID()
	payload, err := payloadWithEventID(value, id.Hex())
	if err != nil {
		return OutboxMessage{}, err
	}
	return OutboxMessage{
		ID:        id,
		Key:       key,
		Payload:   payload,
		CreatedAt: createdAt.UTC(),
	}, nil
}

func payloadWithEventID(value any, eventID string) ([]byte, error) {
	payload, err := json.Marshal(value)
	if err != nil {
		return nil, err
	}
	var object map[string]json.RawMessage
	if err := json.Unmarshal(payload, &object); err != nil {
		return nil, err
	}
	if object == nil {
		return nil, errors.New("outbox event must be a JSON object")
	}
	encodedID, err := json.Marshal(eventID)
	if err != nil {
		return nil, err
	}
	object["event_id"] = encodedID
	return json.Marshal(object)
}

// FetchUnsent merges pending embedded events from every authoritative domain
// collection.
func (r *OutboxRepository) FetchUnsent(ctx context.Context, limit int) ([]OutboxMessage, error) {
	if limit <= 0 {
		return nil, nil
	}

	sources := []string{
		roomdomain.CollectionSessions,
		roomdomain.CollectionParticipants,
		livedomain.CollectionSessions,
		livedomain.CollectionViewers,
	}
	messages := make([]OutboxMessage, 0, limit)
	for _, source := range sources {
		found, err := r.fetchEmbedded(ctx, source, limit)
		if err != nil {
			return nil, err
		}
		messages = append(messages, found...)
	}
	sort.Slice(messages, func(i, j int) bool {
		if messages[i].CreatedAt.Equal(messages[j].CreatedAt) {
			return messages[i].ID.Hex() < messages[j].ID.Hex()
		}
		return messages[i].CreatedAt.Before(messages[j].CreatedAt)
	})
	if len(messages) > limit {
		messages = messages[:limit]
	}
	return messages, nil
}

func (r *OutboxRepository) fetchEmbedded(ctx context.Context, source string, limit int) ([]OutboxMessage, error) {
	type embeddedResult struct {
		OwnerID bson.ObjectID `bson:"owner_id"`
		Message OutboxMessage `bson:"message"`
	}
	pipeline := mongo.Pipeline{
		bson.D{{Key: "$unwind", Value: "$" + outboxField}},
		bson.D{{Key: "$match", Value: bson.D{
			{Key: outboxField + ".sent_at", Value: nil},
			{Key: outboxField + ".failed_at", Value: nil},
		}}},
		bson.D{{Key: "$project", Value: bson.D{
			{Key: "_id", Value: 0},
			{Key: "owner_id", Value: "$_id"},
			{Key: "message", Value: "$" + outboxField},
		}}},
		bson.D{{Key: "$sort", Value: bson.D{{Key: "message.created_at", Value: 1}, {Key: "message._id", Value: 1}}}},
		bson.D{{Key: "$limit", Value: int64(limit)}},
	}
	cur, err := r.db.Collection(source).Aggregate(ctx, pipeline)
	if err != nil {
		return nil, err
	}
	defer func() { _ = cur.Close(ctx) }()

	var rows []embeddedResult
	if err := cur.All(ctx, &rows); err != nil {
		return nil, err
	}
	messages := make([]OutboxMessage, 0, len(rows))
	for _, row := range rows {
		row.Message.Source = source
		row.Message.OwnerID = row.OwnerID
		messages = append(messages, row.Message)
	}
	return messages, nil
}

func (r *OutboxRepository) MarkSent(ctx context.Context, message OutboxMessage, sentAt time.Time) error {
	_, err := r.db.Collection(message.Source).UpdateOne(
		ctx,
		bson.D{{Key: "_id", Value: message.OwnerID}},
		bson.D{{Key: "$pull", Value: bson.D{{Key: outboxField, Value: bson.D{{Key: "_id", Value: message.ID}}}}}},
	)
	return err
}

func (r *OutboxRepository) IncrementAttempts(ctx context.Context, message OutboxMessage) error {
	_, err := r.db.Collection(message.Source).UpdateOne(
		ctx,
		bson.D{{Key: "_id", Value: message.OwnerID}},
		bson.D{{Key: "$inc", Value: bson.D{{Key: outboxField + ".$[event].attempts", Value: 1}}}},
		options.UpdateOne().SetArrayFilters([]any{bson.D{{Key: "event._id", Value: message.ID}}}),
	)
	return err
}

func (r *OutboxRepository) MarkFailed(ctx context.Context, message OutboxMessage, failedAt time.Time) error {
	_, err := r.db.Collection(message.Source).UpdateOne(
		ctx,
		bson.D{{Key: "_id", Value: message.OwnerID}},
		bson.D{{Key: "$set", Value: bson.D{{Key: outboxField + ".$[event].failed_at", Value: failedAt.UTC()}}}},
		options.UpdateOne().SetArrayFilters([]any{bson.D{{Key: "event._id", Value: message.ID}}}),
	)
	return err
}

// RecoverPendingCleanup completes the durable cleanup saga recorded on an
// ended session. Re-running after a crash is safe because both participant
// updates and clearing cleanup_pending are idempotent.
func (r *OutboxRepository) RecoverPendingCleanup(ctx context.Context, limit int) error {
	if err := r.recoverCollectionCleanup(
		ctx,
		roomdomain.CollectionSessions,
		roomdomain.CollectionParticipants,
		limit,
	); err != nil {
		return err
	}
	return r.recoverCollectionCleanup(
		ctx,
		livedomain.CollectionSessions,
		livedomain.CollectionViewers,
		limit,
	)
}

func (r *OutboxRepository) recoverCollectionCleanup(
	ctx context.Context,
	sessionCollection string,
	participantCollection string,
	limit int,
) error {
	type cleanupSession struct {
		ID        bson.ObjectID `bson:"_id"`
		SessionID string        `bson:"session_id"`
		EndedAt   *time.Time    `bson:"ended_at,omitempty"`
	}
	cur, err := r.db.Collection(sessionCollection).Find(
		ctx,
		bson.D{{Key: "cleanup_pending", Value: true}},
		options.Find().SetSort(bson.D{{Key: "ended_at", Value: 1}}).SetLimit(int64(limit)),
	)
	if err != nil {
		return err
	}
	defer func() { _ = cur.Close(ctx) }()

	var sessions []cleanupSession
	if err := cur.All(ctx, &sessions); err != nil {
		return err
	}
	for _, session := range sessions {
		cleanupAt := time.Now().UTC()
		if session.EndedAt != nil {
			cleanupAt = session.EndedAt.UTC()
		}
		if _, err := r.db.Collection(participantCollection).UpdateMany(
			ctx,
			bson.D{{Key: "session_id", Value: session.SessionID}, {Key: "left_at", Value: nil}},
			bson.D{{Key: "$set", Value: bson.D{{Key: "left_at", Value: cleanupAt}}}},
		); err != nil {
			return err
		}
		if _, err := r.db.Collection(sessionCollection).UpdateOne(
			ctx,
			bson.D{{Key: "_id", Value: session.ID}, {Key: "cleanup_pending", Value: true}},
			bson.D{
				{Key: "$set", Value: bson.D{{Key: "cleanup_completed_at", Value: time.Now().UTC()}}},
				{Key: "$unset", Value: bson.D{{Key: "cleanup_pending", Value: ""}}},
			},
		); err != nil {
			return err
		}
	}
	return nil
}
