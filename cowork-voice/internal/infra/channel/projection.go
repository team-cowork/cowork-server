package channel

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"time"

	"go.mongodb.org/mongo-driver/v2/bson"
	"go.mongodb.org/mongo-driver/v2/mongo"
	"go.mongodb.org/mongo-driver/v2/mongo/options"

	"github.com/cowork/cowork-voice/internal/apperr"
)

const CollectionMemberships = "channel_memberships"

var ErrInvalidEvent = errors.New("invalid channel member event")

type Membership struct {
	ChannelID     int64     `bson:"channel_id"`
	TeamID        int64     `bson:"team_id"`
	UserID        int64     `bson:"user_id"`
	Role          string    `bson:"role"`
	ChannelType   string    `bson:"channel_type"`
	Active        bool      `bson:"active"`
	OccurredAt    time.Time `bson:"occurred_at"`
	SourceVersion int64     `bson:"source_version"`
}

type MembershipStore interface {
	Upsert(ctx context.Context, membership Membership) error
	Deactivate(ctx context.Context, membership Membership) error
}

type Projection struct {
	col *mongo.Collection
}

func NewProjection(db *mongo.Database) *Projection {
	return &Projection{col: db.Collection(CollectionMemberships)}
}

func CreateIndexes(ctx context.Context, db *mongo.Database) error {
	_, err := db.Collection(CollectionMemberships).Indexes().CreateMany(ctx, []mongo.IndexModel{
		{
			Keys:    bson.D{{Key: "channel_id", Value: 1}, {Key: "user_id", Value: 1}},
			Options: options.Index().SetUnique(true),
		},
		{
			Keys: bson.D{{Key: "user_id", Value: 1}, {Key: "active", Value: 1}},
		},
	})
	if err != nil {
		return fmt.Errorf("channel membership projection index creation failed: %w", err)
	}
	return nil
}

func (p *Projection) VerifyMembership(ctx context.Context, channelID, userID int64) (int64, error) {
	var membership Membership
	err := p.col.FindOne(ctx, bson.D{
		{Key: "channel_id", Value: channelID},
		{Key: "user_id", Value: userID},
		{Key: "active", Value: true},
	}).Decode(&membership)
	if errors.Is(err, mongo.ErrNoDocuments) {
		return 0, apperr.NotMember()
	}
	if err != nil {
		slog.Error("channel membership projection lookup failed", "err", err, "channel_id", channelID, "user_id", userID)
		return 0, apperr.ServiceUnavailable("channel membership projection unavailable")
	}
	return membership.TeamID, nil
}

func (p *Projection) Upsert(ctx context.Context, membership Membership) error {
	membership.Active = true
	return p.apply(ctx, membership, false)
}

func (p *Projection) Deactivate(ctx context.Context, membership Membership) error {
	membership.Active = false
	return p.apply(ctx, membership, true)
}

func (p *Projection) apply(ctx context.Context, membership Membership, allowEqual bool) error {
	comparison := "$lt"
	if allowEqual {
		comparison = "$lte"
	}
	filter := bson.D{
		{Key: "channel_id", Value: membership.ChannelID},
		{Key: "user_id", Value: membership.UserID},
		{Key: "$or", Value: bson.A{
			bson.D{{Key: "source_version", Value: bson.D{{Key: "$exists", Value: false}}}},
			bson.D{{Key: "source_version", Value: bson.D{{Key: comparison, Value: membership.SourceVersion}}}},
		}},
	}
	update := bson.D{{Key: "$set", Value: membership}}
	_, err := p.col.UpdateOne(ctx, filter, update, options.UpdateOne().SetUpsert(true))
	if mongo.IsDuplicateKeyError(err) {
		// A newer event already owns the unique (channel_id, user_id) row. The stale
		// event deliberately matches no row and its attempted upsert hits this key.
		return nil
	}
	if err != nil {
		return fmt.Errorf("apply channel membership projection: %w", err)
	}
	return nil
}

type EventHandler struct {
	store MembershipStore
}

func NewEventHandler(store MembershipStore) *EventHandler {
	return &EventHandler{store: store}
}

type memberEvent struct {
	EventType   string `json:"eventType"`
	ChannelID   int64  `json:"channelId"`
	TeamID      *int64 `json:"teamId"`
	UserID      int64  `json:"userId"`
	Role        string `json:"role"`
	ChannelType string `json:"channelType"`
	OccurredAt  string `json:"occurredAt"`
}

func (h *EventHandler) Handle(ctx context.Context, key string, payload []byte) error {
	var event memberEvent
	if err := json.Unmarshal(payload, &event); err != nil {
		return fmt.Errorf("%w: decode payload: %v", ErrInvalidEvent, err)
	}
	if event.ChannelID <= 0 || event.UserID <= 0 {
		return fmt.Errorf("%w: channelId and userId must be positive", ErrInvalidEvent)
	}
	expectedKey := fmt.Sprintf("%d:%d", event.ChannelID, event.UserID)
	if key != expectedKey {
		return fmt.Errorf("%w: key %q does not match %q", ErrInvalidEvent, key, expectedKey)
	}
	occurredAt, err := parseOccurredAt(event.OccurredAt)
	if err != nil {
		return fmt.Errorf("%w: occurredAt: %v", ErrInvalidEvent, err)
	}
	teamID := int64(0)
	if event.TeamID != nil {
		teamID = *event.TeamID
	}
	membership := Membership{
		ChannelID:     event.ChannelID,
		TeamID:        teamID,
		UserID:        event.UserID,
		Role:          event.Role,
		ChannelType:   event.ChannelType,
		OccurredAt:    occurredAt,
		SourceVersion: occurredAt.UnixNano(),
	}

	switch event.EventType {
	case "JOIN":
		return h.store.Upsert(ctx, membership)
	case "LEAVE":
		return h.store.Deactivate(ctx, membership)
	default:
		return fmt.Errorf("%w: unsupported eventType %q", ErrInvalidEvent, event.EventType)
	}
}

func parseOccurredAt(value string) (time.Time, error) {
	parsed, err := time.Parse(time.RFC3339Nano, value)
	if err != nil {
		return time.Time{}, err
	}
	return parsed.UTC(), nil
}
