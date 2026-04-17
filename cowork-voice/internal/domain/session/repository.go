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

	"github.com/cowork/cowork-voice/internal/apperr"
)

type Repository interface {
	FindActiveSession(ctx context.Context, channelID int64) (*VoiceSession, error)
	FindSessionByRoomName(ctx context.Context, roomName string) (*VoiceSession, error)
	CreateSession(ctx context.Context, channelID, teamID int64) (*VoiceSession, error)
	GetSession(ctx context.Context, sessionID string) (*VoiceSession, error)
	EndSession(ctx context.Context, sessionID string, endedAt time.Time) error
	MarkSessionStarted(ctx context.Context, sessionID string, startedAt time.Time) (bool, error)
	InsertParticipant(ctx context.Context, p *VoiceParticipant) error
	MarkParticipantLeft(ctx context.Context, sessionID string, userID int64, now time.Time) (bool, error)
	CleanupOrphanParticipants(ctx context.Context, sessionID string, now time.Time) (int64, error)
	GetParticipantJoinedAt(ctx context.Context, sessionID string, userID int64) (*time.Time, error)
}

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
		{
			Keys:    bson.D{{Key: "room_name", Value: 1}},
			Options: options.Index().SetUnique(true),
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

type mongoSessionRepository struct {
	db *mongo.Database
}

func NewMongoSessionRepository(db *mongo.Database) Repository {
	return &mongoSessionRepository{db: db}
}

func (r *mongoSessionRepository) FindActiveSession(ctx context.Context, channelID int64) (*VoiceSession, error) {
	col := r.db.Collection(CollectionSessions)
	filter := bson.D{{Key: "channel_id", Value: channelID}, {Key: "status", Value: StatusActive}}
	var s VoiceSession
	err := col.FindOne(ctx, filter).Decode(&s)
	if errors.Is(err, mongo.ErrNoDocuments) {
		return nil, nil
	}
	if err != nil {
		return nil, apperr.Internal(err.Error())
	}
	return &s, nil
}

func (r *mongoSessionRepository) FindSessionByRoomName(ctx context.Context, roomName string) (*VoiceSession, error) {
	col := r.db.Collection(CollectionSessions)
	filter := bson.D{{Key: "room_name", Value: roomName}}
	var s VoiceSession
	err := col.FindOne(ctx, filter).Decode(&s)
	if errors.Is(err, mongo.ErrNoDocuments) {
		return nil, nil
	}
	if err != nil {
		return nil, apperr.Internal(err.Error())
	}
	return &s, nil
}

func (r *mongoSessionRepository) CreateSession(ctx context.Context, channelID, teamID int64) (*VoiceSession, error) {
	col := r.db.Collection(CollectionSessions)
	now := time.Now().UTC()
	sessionID := uuid.NewString()
	s := &VoiceSession{
		SessionID: sessionID,
		ChannelID: channelID,
		TeamID:    teamID,
		RoomName:  RoomName(channelID, sessionID),
		Status:    StatusActive,
		StartedAt: now,
	}
	_, err := col.InsertOne(ctx, s)
	if err != nil {
		if mongo.IsDuplicateKeyError(err) {
			// 동시 첫 입장 경쟁 조건: 다른 요청이 먼저 생성함 → 해당 세션 반환
			return r.FindActiveSession(ctx, channelID)
		}
		return nil, apperr.Internal(err.Error())
	}
	return s, nil
}

func (r *mongoSessionRepository) GetSession(ctx context.Context, sessionID string) (*VoiceSession, error) {
	col := r.db.Collection(CollectionSessions)
	filter := bson.D{{Key: "session_id", Value: sessionID}}
	var s VoiceSession
	err := col.FindOne(ctx, filter).Decode(&s)
	if errors.Is(err, mongo.ErrNoDocuments) {
		return nil, nil
	}
	if err != nil {
		return nil, apperr.Internal(err.Error())
	}
	return &s, nil
}

func (r *mongoSessionRepository) EndSession(ctx context.Context, sessionID string, endedAt time.Time) error {
	col := r.db.Collection(CollectionSessions)
	filter := bson.D{{Key: "session_id", Value: sessionID}, {Key: "status", Value: StatusActive}}
	update := bson.D{{Key: "$set", Value: bson.D{
		{Key: "status", Value: StatusEnded},
		{Key: "ended_at", Value: endedAt},
	}}}
	_, err := col.UpdateOne(ctx, filter, update)
	if err != nil {
		return apperr.Internal(err.Error())
	}
	return nil
}

func (r *mongoSessionRepository) MarkSessionStarted(ctx context.Context, sessionID string, startedAt time.Time) (bool, error) {
	col := r.db.Collection(CollectionSessions)
	filter := bson.D{
		{Key: "session_id", Value: sessionID},
		{Key: "status", Value: StatusActive},
		{Key: "started_event_sent_at", Value: bson.D{{Key: "$exists", Value: false}}},
	}
	update := bson.D{{Key: "$set", Value: bson.D{
		{Key: "started_at", Value: startedAt},
		{Key: "started_event_sent_at", Value: startedAt},
	}}}
	result, err := col.UpdateOne(ctx, filter, update)
	if err != nil {
		return false, apperr.Internal(err.Error())
	}
	return result.ModifiedCount == 1, nil
}

func (r *mongoSessionRepository) InsertParticipant(ctx context.Context, p *VoiceParticipant) error {
	col := r.db.Collection(CollectionParticipants)
	filter := bson.D{
		{Key: "session_id", Value: p.SessionID},
		{Key: "user_id", Value: p.UserID},
		{Key: "left_at", Value: bson.D{{Key: "$exists", Value: false}}},
	}
	update := bson.D{
		{Key: "$setOnInsert", Value: bson.D{
			{Key: "session_id", Value: p.SessionID},
			{Key: "user_id", Value: p.UserID},
			{Key: "channel_id", Value: p.ChannelID},
			{Key: "joined_at", Value: p.JoinedAt},
		}},
	}
	_, err := col.UpdateOne(ctx, filter, update, options.UpdateOne().SetUpsert(true))
	if err != nil {
		return apperr.Internal(err.Error())
	}
	return nil
}

func (r *mongoSessionRepository) MarkParticipantLeft(ctx context.Context, sessionID string, userID int64, now time.Time) (bool, error) {
	col := r.db.Collection(CollectionParticipants)
	filter := bson.D{
		{Key: "session_id", Value: sessionID},
		{Key: "user_id", Value: userID},
		{Key: "left_at", Value: bson.D{{Key: "$exists", Value: false}}},
	}
	update := bson.D{{Key: "$set", Value: bson.D{{Key: "left_at", Value: now}}}}
	result, err := col.UpdateOne(ctx, filter, update)
	if err != nil {
		return false, apperr.Internal(err.Error())
	}
	return result.ModifiedCount == 1, nil
}

func (r *mongoSessionRepository) CleanupOrphanParticipants(ctx context.Context, sessionID string, now time.Time) (int64, error) {
	col := r.db.Collection(CollectionParticipants)
	filter := bson.D{
		{Key: "session_id", Value: sessionID},
		{Key: "left_at", Value: bson.D{{Key: "$exists", Value: false}}},
	}
	update := bson.D{{Key: "$set", Value: bson.D{{Key: "left_at", Value: now}}}}
	result, err := col.UpdateMany(ctx, filter, update)
	if err != nil {
		return 0, apperr.Internal(err.Error())
	}
	return result.ModifiedCount, nil
}

func (r *mongoSessionRepository) GetParticipantJoinedAt(ctx context.Context, sessionID string, userID int64) (*time.Time, error) {
	col := r.db.Collection(CollectionParticipants)
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
		return nil, apperr.Internal(err.Error())
	}
	return &p.JoinedAt, nil
}
