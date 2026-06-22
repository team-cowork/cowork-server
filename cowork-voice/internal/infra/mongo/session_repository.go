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
	}
	if _, err := sessions.Indexes().CreateMany(ctx, sessionIndexes); err != nil {
		return fmt.Errorf("voice_sessions index creation failed: %w", err)
	}

	participantIndexes := []mongo.IndexModel{
		// 세션당 user는 "활성 참가자(left_at=null)" 한 명만 허용(재입장은 left_at이 채워진 옛 문서를
		// 인덱스에서 제외하므로 허용). InsertParticipant가 left_at을 항상 명시적으로 null로 set하므로
		// "missing vs null" 모호성은 발생하지 않으며, null 동등 필터는 누락 필드까지 포함해 더 안전하다.
		{
			Keys: bson.D{{Key: "session_id", Value: 1}, {Key: "user_id", Value: 1}},
			Options: options.Index().
				SetUnique(true).
				SetPartialFilterExpression(bson.D{{Key: "left_at", Value: nil}}),
		},
		{
			Keys: bson.D{{Key: "user_id", Value: 1}, {Key: "joined_at", Value: 1}},
		},
	}
	if _, err := participants.Indexes().CreateMany(ctx, participantIndexes); err != nil {
		return fmt.Errorf("voice_participants index creation failed: %w", err)
	}

	outbox := db.Collection(CollectionOutbox)
	outboxIndexes := []mongo.IndexModel{
		// relay 조회: 미전송(sent_at=null)·미격리(failed_at=null) 메시지를 생성 순서대로 스캔
		{
			Keys: bson.D{{Key: "sent_at", Value: 1}, {Key: "failed_at", Value: 1}, {Key: "created_at", Value: 1}},
		},
		// 전송 완료된 메시지를 24h 후 자동 정리(TTL). sent_at이 없는 미전송 문서는 대상 아님.
		{
			Keys:    bson.D{{Key: "sent_at", Value: 1}},
			Options: options.Index().SetExpireAfterSeconds(24 * 60 * 60),
		},
	}
	if _, err := outbox.Indexes().CreateMany(ctx, outboxIndexes); err != nil {
		return fmt.Errorf("voice_outbox index creation failed: %w", err)
	}

	return nil
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

func (r *mongoSessionRepository) MarkSessionStarted(ctx context.Context, sessionID string, startedAt time.Time) (bool, error) {
	col := r.db.Collection(room.CollectionSessions)
	filter := bson.D{
		{Key: "session_id", Value: sessionID},
		{Key: "status", Value: room.StatusActive},
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

func (r *mongoSessionRepository) InsertParticipant(ctx context.Context, p *room.VoiceParticipant) error {
	col := r.db.Collection(room.CollectionParticipants)
	filter := bson.D{
		{Key: "session_id", Value: p.SessionID},
		{Key: "user_id", Value: p.UserID},
		{Key: "left_at", Value: nil},
	}
	update := bson.D{
		{Key: "$setOnInsert", Value: bson.D{
			{Key: "session_id", Value: p.SessionID},
			{Key: "user_id", Value: p.UserID},
			{Key: "channel_id", Value: p.ChannelID},
			{Key: "joined_at", Value: p.JoinedAt},
			{Key: "left_at", Value: nil},
		}},
	}
	_, err := col.UpdateOne(ctx, filter, update, options.UpdateOne().SetUpsert(true))
	if err != nil {
		return apperr.Internal(err.Error())
	}
	return nil
}

func (r *mongoSessionRepository) MarkParticipantLeft(ctx context.Context, sessionID string, userID int64, now time.Time) (bool, error) {
	col := r.db.Collection(room.CollectionParticipants)
	filter := bson.D{
		{Key: "session_id", Value: sessionID},
		{Key: "user_id", Value: userID},
		{Key: "left_at", Value: nil},
	}
	update := bson.D{{Key: "$set", Value: bson.D{{Key: "left_at", Value: now}}}}
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
	return result.ModifiedCount, nil
}

func (r *mongoSessionRepository) GetParticipantJoinedAt(ctx context.Context, sessionID string, userID int64) (*time.Time, error) {
	col := r.db.Collection(room.CollectionParticipants)
	filter := bson.D{
		{Key: "session_id", Value: sessionID},
		{Key: "user_id", Value: userID},
		{Key: "left_at", Value: nil},
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
