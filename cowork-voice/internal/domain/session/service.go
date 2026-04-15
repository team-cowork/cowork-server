package session

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/google/uuid"
	"go.mongodb.org/mongo-driver/v2/bson"
	"go.mongodb.org/mongo-driver/v2/mongo"
	"go.mongodb.org/mongo-driver/v2/mongo/options"

	"github.com/cowork/cowork-voice/internal/apperror"
)

func CreateIndexes(ctx context.Context, db *mongo.Database) error {
	sessions := db.Collection(CollectionSessions)
	participants := db.Collection(CollectionParticipants)

	sessionIndexes := []mongo.IndexModel{
		{
			Keys:    bson.D{{Key: "session_id", Value: 1}},
			Options: options.Index().SetUnique(true),
		},
		{
			Keys: bson.D{{Key: "channel_id", Value: 1}},
			Options: options.Index().
				SetUnique(true).
				SetPartialFilterExpression(bson.D{{Key: "status", Value: bson.D{{Key: "$eq", Value: "active"}}}}),
		},
		{
			Keys: bson.D{{Key: "channel_id", Value: 1}, {Key: "status", Value: 1}},
		},
	}
	if _, err := sessions.Indexes().CreateMany(ctx, sessionIndexes); err != nil {
		return fmt.Errorf("voice_sessions index creation failed: %w", err)
	}

	participantIndexes := []mongo.IndexModel{
		{
			Keys: bson.D{{Key: "session_id", Value: 1}, {Key: "user_id", Value: 1}},
			Options: options.Index().
				SetUnique(true).
				SetPartialFilterExpression(bson.D{{Key: "left_at", Value: bson.D{{Key: "$exists", Value: false}}}}),
		},
		{
			Keys: bson.D{{Key: "user_id", Value: 1}, {Key: "joined_at", Value: 1}},
		},
	}
	if _, err := participants.Indexes().CreateMany(ctx, participantIndexes); err != nil {
		return fmt.Errorf("voice_participants index creation failed: %w", err)
	}

	return nil
}

func FindActiveSession(ctx context.Context, db *mongo.Database, channelID int64) (*VoiceSession, error) {
	col := db.Collection(CollectionSessions)
	filter := bson.D{{Key: "channel_id", Value: channelID}, {Key: "status", Value: StatusActive}}
	var s VoiceSession
	err := col.FindOne(ctx, filter).Decode(&s)
	if errors.Is(err, mongo.ErrNoDocuments) {
		return nil, nil
	}
	if err != nil {
		return nil, apperror.Internal(err.Error())
	}
	return &s, nil
}

func FindLatestSessionByChannel(ctx context.Context, db *mongo.Database, channelID int64) (*VoiceSession, error) {
	col := db.Collection(CollectionSessions)
	filter := bson.D{{Key: "channel_id", Value: channelID}}
	opts := options.FindOne().SetSort(bson.D{{Key: "started_at", Value: -1}})
	var s VoiceSession
	err := col.FindOne(ctx, filter, opts).Decode(&s)
	if errors.Is(err, mongo.ErrNoDocuments) {
		return nil, nil
	}
	if err != nil {
		return nil, apperror.Internal(err.Error())
	}
	return &s, nil
}

func CreateSession(ctx context.Context, db *mongo.Database, channelID, teamID int64) (*VoiceSession, error) {
	col := db.Collection(CollectionSessions)
	now := time.Now().UTC()
	s := &VoiceSession{
		SessionID: uuid.NewString(),
		ChannelID: channelID,
		TeamID:    teamID,
		RoomName:  RoomName(channelID),
		Status:    StatusActive,
		StartedAt: now,
	}
	_, err := col.InsertOne(ctx, s)
	if err != nil {
		if isDuplicateKeyError(err) {
			// 동시 첫 입장 경쟁 조건: 다른 요청이 먼저 생성함 → 해당 세션 반환
			return FindActiveSession(ctx, db, channelID)
		}
		return nil, apperror.Internal(err.Error())
	}
	return s, nil
}

func EndSession(ctx context.Context, db *mongo.Database, sessionID string, endedAt time.Time) error {
	col := db.Collection(CollectionSessions)
	filter := bson.D{{Key: "session_id", Value: sessionID}, {Key: "status", Value: StatusActive}}
	update := bson.D{{Key: "$set", Value: bson.D{
		{Key: "status", Value: StatusEnded},
		{Key: "ended_at", Value: endedAt},
	}}}
	_, err := col.UpdateOne(ctx, filter, update)
	if err != nil {
		return apperror.Internal(err.Error())
	}
	return nil
}

func MarkSessionStarted(ctx context.Context, db *mongo.Database, sessionID string, startedAt time.Time) (bool, error) {
	col := db.Collection(CollectionSessions)
	filter := bson.D{
		{Key: "session_id", Value: sessionID},
		{Key: "status", Value: StatusActive},
		{Key: "started_event_sent_at", Value: bson.D{{Key: "$exists", Value: false}}},
	}
	update := bson.D{{Key: "$set", Value: bson.D{{Key: "started_event_sent_at", Value: startedAt}}}}
	result, err := col.UpdateOne(ctx, filter, update)
	if err != nil {
		return false, apperror.Internal(err.Error())
	}
	return result.ModifiedCount == 1, nil
}

func InsertParticipant(ctx context.Context, db *mongo.Database, p *VoiceParticipant) error {
	col := db.Collection(CollectionParticipants)
	filter := bson.D{
		{Key: "session_id", Value: p.SessionID},
		{Key: "user_id", Value: p.UserID},
		{Key: "left_at", Value: bson.D{{Key: "$exists", Value: false}}},
	}
	update := bson.D{
		{Key: "$set", Value: bson.D{
			{Key: "joined_at", Value: p.JoinedAt},
		}},
		{Key: "$setOnInsert", Value: bson.D{
			{Key: "session_id", Value: p.SessionID},
			{Key: "user_id", Value: p.UserID},
			{Key: "channel_id", Value: p.ChannelID},
		}},
	}
	_, err := col.UpdateOne(ctx, filter, update, options.UpdateOne().SetUpsert(true))
	if err != nil {
		return apperror.Internal(err.Error())
	}
	return nil
}

func MarkParticipantLeft(ctx context.Context, db *mongo.Database, sessionID string, userID int64, now time.Time) (bool, error) {
	col := db.Collection(CollectionParticipants)
	filter := bson.D{
		{Key: "session_id", Value: sessionID},
		{Key: "user_id", Value: userID},
		{Key: "left_at", Value: bson.D{{Key: "$exists", Value: false}}},
	}
	update := bson.D{{Key: "$set", Value: bson.D{{Key: "left_at", Value: now}}}}
	result, err := col.UpdateOne(ctx, filter, update)
	if err != nil {
		return false, apperror.Internal(err.Error())
	}
	return result.ModifiedCount == 1, nil
}

func CleanupOrphanParticipants(ctx context.Context, db *mongo.Database, sessionID string, now time.Time) (int64, error) {
	col := db.Collection(CollectionParticipants)
	filter := bson.D{
		{Key: "session_id", Value: sessionID},
		{Key: "left_at", Value: bson.D{{Key: "$exists", Value: false}}},
	}
	update := bson.D{{Key: "$set", Value: bson.D{{Key: "left_at", Value: now}}}}
	result, err := col.UpdateMany(ctx, filter, update)
	if err != nil {
		return 0, apperror.Internal(err.Error())
	}
	return result.ModifiedCount, nil
}

func GetSession(ctx context.Context, db *mongo.Database, sessionID string) (*VoiceSession, error) {
	col := db.Collection(CollectionSessions)
	filter := bson.D{{Key: "session_id", Value: sessionID}}
	var s VoiceSession
	err := col.FindOne(ctx, filter).Decode(&s)
	if errors.Is(err, mongo.ErrNoDocuments) {
		return nil, nil
	}
	if err != nil {
		return nil, apperror.Internal(err.Error())
	}
	return &s, nil
}

func GetParticipantJoinedAt(ctx context.Context, db *mongo.Database, sessionID string, userID int64) (*time.Time, error) {
	col := db.Collection(CollectionParticipants)
	filter := bson.D{
		{Key: "session_id", Value: sessionID},
		{Key: "user_id", Value: userID},
		{Key: "left_at", Value: bson.D{{Key: "$exists", Value: false}}},
	}
	var p VoiceParticipant
	err := col.FindOne(ctx, filter).Decode(&p)
	if errors.Is(err, mongo.ErrNoDocuments) {
		return nil, nil
	}
	if err != nil {
		return nil, apperror.Internal(err.Error())
	}
	return &p.JoinedAt, nil
}

func isDuplicateKeyError(err error) bool {
	return mongo.IsDuplicateKeyError(err)
}
