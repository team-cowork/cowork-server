package mongo

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
	room "github.com/cowork/cowork-voice/internal/domain/voice_room"
)

func CreateIndexes(ctx context.Context, db *mongo.Database) error {
	sessions := db.Collection(room.CollectionSessions)
	participants := db.Collection(room.CollectionParticipants)

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
		{Keys: bson.D{{Key: outboxField + ".created_at", Value: 1}}},
	}
	if _, err := sessions.Indexes().CreateMany(ctx, sessionIndexes); err != nil {
		return fmt.Errorf("voice_sessions index creation failed: %w", err)
	}

	participantIndexes := []mongo.IndexModel{
		// 세션당 user는 "활성 참가자(left_at=null)" 한 명만 허용(재입장은 left_at이 채워진 문서를
		// 인덱스에서 제외하므로 허용). RecordParticipantJoinedAndEnqueue가 left_at을 항상 null로 기록한다.
		{
			Keys: bson.D{{Key: "session_id", Value: 1}, {Key: "user_id", Value: 1}},
			Options: options.Index().
				SetUnique(true).
				SetPartialFilterExpression(bson.D{{Key: "left_at", Value: nil}}),
		},
		{
			Keys: bson.D{{Key: "user_id", Value: 1}, {Key: "joined_at", Value: 1}},
		},
		{
			Keys: bson.D{{Key: "session_id", Value: 1}, {Key: "occurrence_id", Value: 1}},
			Options: options.Index().
				SetUnique(true).
				SetPartialFilterExpression(bson.D{{Key: "occurrence_id", Value: bson.D{{Key: "$type", Value: "string"}}}}),
		},
		{Keys: bson.D{{Key: outboxField + ".created_at", Value: 1}}},
	}
	if _, err := participants.Indexes().CreateMany(ctx, participantIndexes); err != nil {
		return fmt.Errorf("voice_participants index creation failed: %w", err)
	}

	return CreateLiveIndexes(ctx, db)
}

type mongoSessionRepository struct {
	db *mongo.Database
}

func NewMongoSessionRepository(db *mongo.Database) *mongoSessionRepository {
	return &mongoSessionRepository{db: db}
}

func (r *mongoSessionRepository) FindActiveSession(ctx context.Context, channelID int64) (*room.VoiceSession, error) {
	col := r.db.Collection(room.CollectionSessions)
	filter := bson.D{{Key: "channel_id", Value: channelID}, {Key: "status", Value: room.StatusActive}}
	var s room.VoiceSession
	err := col.FindOne(ctx, filter).Decode(&s)
	if errors.Is(err, mongo.ErrNoDocuments) {
		return nil, nil
	}
	if err != nil {
		return nil, apperr.Internal(err.Error())
	}
	return &s, nil
}

func (r *mongoSessionRepository) FindSessionByRoomName(ctx context.Context, roomName string) (*room.VoiceSession, error) {
	col := r.db.Collection(room.CollectionSessions)
	filter := bson.D{{Key: "room_name", Value: roomName}}
	var s room.VoiceSession
	err := col.FindOne(ctx, filter).Decode(&s)
	if errors.Is(err, mongo.ErrNoDocuments) {
		return nil, nil
	}
	if err != nil {
		return nil, apperr.Internal(err.Error())
	}
	return &s, nil
}

func (r *mongoSessionRepository) CreateSession(ctx context.Context, channelID, teamID int64) (*room.VoiceSession, bool, error) {
	col := r.db.Collection(room.CollectionSessions)
	now := time.Now().UTC()
	sessionID := uuid.NewString()
	s := &room.VoiceSession{
		SessionID: sessionID,
		ChannelID: channelID,
		TeamID:    teamID,
		RoomName:  room.RoomName(channelID, sessionID),
		Status:    room.StatusActive,
		StartedAt: now,
	}
	_, err := col.InsertOne(ctx, s)
	if err != nil {
		if mongo.IsDuplicateKeyError(err) {
			// 동시 첫 입장 경쟁 조건: 다른 요청이 먼저 생성함 → 기존 세션을 created=false로 반환
			existing, ferr := r.FindActiveSession(ctx, channelID)
			return existing, false, ferr
		}
		return nil, false, apperr.Internal(err.Error())
	}
	return s, true, nil
}

func (r *mongoSessionRepository) GetSession(ctx context.Context, sessionID string) (*room.VoiceSession, error) {
	col := r.db.Collection(room.CollectionSessions)
	filter := bson.D{{Key: "session_id", Value: sessionID}}
	var s room.VoiceSession
	err := col.FindOne(ctx, filter).Decode(&s)
	if errors.Is(err, mongo.ErrNoDocuments) {
		return nil, nil
	}
	if err != nil {
		return nil, apperr.Internal(err.Error())
	}
	return &s, nil
}

func (r *mongoSessionRepository) EndSession(ctx context.Context, sessionID string, endedAt time.Time) (bool, error) {
	col := r.db.Collection(room.CollectionSessions)
	filter := bson.D{{Key: "session_id", Value: sessionID}, {Key: "status", Value: room.StatusActive}}
	update := bson.D{{Key: "$set", Value: bson.D{
		{Key: "status", Value: room.StatusEnded},
		{Key: "ended_at", Value: endedAt},
	}}}
	result, err := col.UpdateOne(ctx, filter, update)
	if err != nil {
		return false, apperr.Internal(err.Error())
	}
	return result.ModifiedCount == 1, nil
}

func (r *mongoSessionRepository) MarkSessionStartedAndEnqueue(
	ctx context.Context,
	sessionID string,
	startedAt time.Time,
	event any,
) (bool, error) {
	message, err := newOutboxMessage(sessionID, event, startedAt)
	if err != nil {
		return false, apperr.Internal(err.Error())
	}
	col := r.db.Collection(room.CollectionSessions)
	filter := bson.D{
		{Key: "session_id", Value: sessionID},
		{Key: "status", Value: room.StatusActive},
		{Key: "started_event_sent_at", Value: bson.D{{Key: "$exists", Value: false}}},
	}
	update := bson.D{
		{Key: "$set", Value: bson.D{
			{Key: "started_at", Value: startedAt},
			// This timestamp records durable enqueue, not Kafka delivery.
			{Key: "started_event_sent_at", Value: startedAt},
		}},
		{Key: "$push", Value: bson.D{{Key: outboxField, Value: message}}},
	}
	result, err := col.UpdateOne(ctx, filter, update)
	if err != nil {
		return false, apperr.Internal(err.Error())
	}
	return result.ModifiedCount == 1, nil
}

func (r *mongoSessionRepository) RecordParticipantJoinedAndEnqueue(
	ctx context.Context,
	p *room.VoiceParticipant,
	occurrenceID string,
	event any,
) (bool, error) {
	message, err := newOutboxMessage(p.SessionID, event, p.JoinedAt)
	if err != nil {
		return false, apperr.Internal(err.Error())
	}
	col := r.db.Collection(room.CollectionParticipants)

	document := bson.D{
		{Key: "session_id", Value: p.SessionID},
		{Key: "user_id", Value: p.UserID},
		{Key: "channel_id", Value: p.ChannelID},
		{Key: "occurrence_id", Value: occurrenceID},
		{Key: "joined_at", Value: p.JoinedAt},
		{Key: "left_at", Value: nil},
		{Key: outboxField, Value: bson.A{message}},
	}
	if _, err := col.InsertOne(ctx, document); err != nil {
		if mongo.IsDuplicateKeyError(err) {
			return false, nil
		}
		return false, apperr.Internal(err.Error())
	}
	return true, nil
}

func (r *mongoSessionRepository) MarkParticipantLeftAndEnqueue(
	ctx context.Context,
	sessionID string,
	userID int64,
	occurrenceID string,
	now time.Time,
	event any,
) (bool, error) {
	message, err := newOutboxMessage(sessionID, event, now)
	if err != nil {
		return false, apperr.Internal(err.Error())
	}
	col := r.db.Collection(room.CollectionParticipants)
	filter := bson.D{
		{Key: "session_id", Value: sessionID},
		{Key: "user_id", Value: userID},
		{Key: "left_at", Value: nil},
	}
	if occurrenceID != "" {
		filter = append(filter, bson.E{Key: "occurrence_id", Value: occurrenceID})
	}
	update := bson.D{
		{Key: "$set", Value: bson.D{{Key: "left_at", Value: now}}},
		{Key: "$push", Value: bson.D{{Key: outboxField, Value: message}}},
	}
	result, err := col.UpdateOne(ctx, filter, update)
	if err != nil {
		return false, apperr.Internal(err.Error())
	}
	return result.ModifiedCount == 1, nil
}

func (r *mongoSessionRepository) CleanupOrphanParticipants(ctx context.Context, sessionID string, now time.Time) (int64, error) {
	col := r.db.Collection(room.CollectionParticipants)
	filter := bson.D{
		{Key: "session_id", Value: sessionID},
		{Key: "left_at", Value: nil},
	}
	update := bson.D{{Key: "$set", Value: bson.D{{Key: "left_at", Value: now}}}}
	result, err := col.UpdateMany(ctx, filter, update)
	if err != nil {
		return 0, apperr.Internal(err.Error())
	}
	if _, err := r.db.Collection(room.CollectionSessions).UpdateOne(
		ctx,
		bson.D{{Key: "session_id", Value: sessionID}, {Key: "cleanup_pending", Value: true}},
		bson.D{
			{Key: "$set", Value: bson.D{{Key: "cleanup_completed_at", Value: time.Now().UTC()}}},
			{Key: "$unset", Value: bson.D{{Key: "cleanup_pending", Value: ""}}},
		},
	); err != nil {
		return result.ModifiedCount, apperr.Internal(err.Error())
	}
	return result.ModifiedCount, nil
}

func (r *mongoSessionRepository) GetParticipantJoinedAt(
	ctx context.Context,
	sessionID string,
	userID int64,
	occurrenceID string,
) (*time.Time, error) {
	col := r.db.Collection(room.CollectionParticipants)
	filter := bson.D{
		{Key: "session_id", Value: sessionID},
		{Key: "user_id", Value: userID},
		{Key: "left_at", Value: nil},
	}
	if occurrenceID != "" {
		filter = append(filter, bson.E{Key: "occurrence_id", Value: occurrenceID})
	}
	var p room.VoiceParticipant
	err := col.FindOne(ctx, filter).Decode(&p)
	if errors.Is(err, mongo.ErrNoDocuments) {
		return nil, nil
	}
	if err != nil {
		return nil, apperr.Internal(err.Error())
	}
	return &p.JoinedAt, nil
}

func (r *mongoSessionRepository) EndSessionAndEnqueue(
	ctx context.Context,
	sessionID string,
	endedAt time.Time,
	event any,
) (bool, error) {
	message, err := newOutboxMessage(sessionID, event, endedAt)
	if err != nil {
		return false, apperr.Internal(err.Error())
	}
	result, err := r.db.Collection(room.CollectionSessions).UpdateOne(
		ctx,
		bson.D{{Key: "session_id", Value: sessionID}, {Key: "status", Value: room.StatusActive}},
		bson.D{
			{Key: "$set", Value: bson.D{
				{Key: "status", Value: room.StatusEnded},
				{Key: "ended_at", Value: endedAt},
				{Key: "cleanup_pending", Value: true},
			}},
			{Key: "$push", Value: bson.D{{Key: outboxField, Value: message}}},
		},
	)
	if err != nil {
		return false, apperr.Internal(err.Error())
	}
	return result.ModifiedCount == 1, nil
}
