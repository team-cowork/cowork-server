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
	"github.com/cowork/cowork-voice/internal/domain/liveroom"
)

func CreateLiveIndexes(ctx context.Context, db *mongo.Database) error {
	sessions := db.Collection(liveroom.CollectionSessions)
	viewers := db.Collection(liveroom.CollectionViewers)

	sessionIndexes := []mongo.IndexModel{
		{
			Keys:    bson.D{{Key: "session_id", Value: 1}},
			Options: options.Index().SetUnique(true),
		},
		// 채널당 활성 라이브 1개 강제. 동시 start 경쟁은 duplicate key로 잡는다.
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
		// 시작 복구 후보 조회(FindCreatingSessionsBefore)용. 부분 필터에는 $exists:false를 쓸 수 없어 status만 건다.
		{
			Keys: bson.D{{Key: "started_at", Value: 1}},
			Options: options.Index().
				SetPartialFilterExpression(bson.D{{Key: "status", Value: bson.D{{Key: "$eq", Value: "active"}}}}),
		},
	}
	if _, err := sessions.Indexes().CreateMany(ctx, sessionIndexes); err != nil {
		return fmt.Errorf("live_sessions index creation failed: %w", err)
	}

	viewerIndexes := []mongo.IndexModel{
		// 세션당 시청자는 "활성(left_at=null)" 한 명만 허용. voice_participants와 동일 패턴 —
		// RecordViewerJoinedAndEnqueue가 left_at을 항상 null로 기록한다.
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
	if _, err := viewers.Indexes().CreateMany(ctx, viewerIndexes); err != nil {
		return fmt.Errorf("live_participants index creation failed: %w", err)
	}

	return nil
}

type MongoLiveSessionRepository struct {
	db *mongo.Database
}

func NewMongoLiveSessionRepository(db *mongo.Database) *MongoLiveSessionRepository {
	return &MongoLiveSessionRepository{db: db}
}

func (r *MongoLiveSessionRepository) FindActiveSession(ctx context.Context, channelID int64) (*liveroom.Session, error) {
	col := r.db.Collection(liveroom.CollectionSessions)
	filter := bson.D{{Key: "channel_id", Value: channelID}, {Key: "status", Value: liveroom.StatusActive}}
	var s liveroom.Session
	err := col.FindOne(ctx, filter).Decode(&s)
	if errors.Is(err, mongo.ErrNoDocuments) {
		return nil, nil
	}
	if err != nil {
		return nil, apperr.Internal(err.Error())
	}
	return &s, nil
}

func (r *MongoLiveSessionRepository) FindSessionByRoomName(ctx context.Context, roomName string) (*liveroom.Session, error) {
	col := r.db.Collection(liveroom.CollectionSessions)
	filter := bson.D{{Key: "room_name", Value: roomName}}
	var s liveroom.Session
	err := col.FindOne(ctx, filter).Decode(&s)
	if errors.Is(err, mongo.ErrNoDocuments) {
		return nil, nil
	}
	if err != nil {
		return nil, apperr.Internal(err.Error())
	}
	return &s, nil
}

func (r *MongoLiveSessionRepository) CreateSession(ctx context.Context, channelID, teamID, hostUserID int64) (*liveroom.Session, bool, error) {
	col := r.db.Collection(liveroom.CollectionSessions)
	now := time.Now().UTC()
	sessionID := uuid.NewString()
	s := &liveroom.Session{
		SessionID:  sessionID,
		ChannelID:  channelID,
		TeamID:     teamID,
		HostUserID: hostUserID,
		RoomName:   liveroom.Name(channelID, sessionID),
		Status:     liveroom.StatusActive,
		StartedAt:  now,
	}
	_, err := col.InsertOne(ctx, s)
	if err != nil {
		if mongo.IsDuplicateKeyError(err) {
			// 동시 start 경쟁 조건: 다른 요청이 먼저 생성함 → 기존 세션을 created=false로 반환
			existing, ferr := r.FindActiveSession(ctx, channelID)
			return existing, false, ferr
		}
		return nil, false, apperr.Internal(err.Error())
	}
	return s, true, nil
}

func (r *MongoLiveSessionRepository) EndSession(ctx context.Context, sessionID string, endedAt time.Time) (bool, error) {
	col := r.db.Collection(liveroom.CollectionSessions)
	filter := bson.D{{Key: "session_id", Value: sessionID}, {Key: "status", Value: liveroom.StatusActive}}
	update := bson.D{{Key: "$set", Value: bson.D{
		{Key: "status", Value: liveroom.StatusEnded},
		{Key: "ended_at", Value: endedAt},
	}}}
	result, err := col.UpdateOne(ctx, filter, update)
	if err != nil {
		return false, apperr.Internal(err.Error())
	}
	return result.ModifiedCount == 1, nil
}

func (r *MongoLiveSessionRepository) FindCreatingSessionsBefore(ctx context.Context, before time.Time) ([]liveroom.Session, error) {
	col := r.db.Collection(liveroom.CollectionSessions)
	filter := bson.D{
		{Key: "status", Value: liveroom.StatusActive},
		{Key: "started_event_sent_at", Value: bson.D{{Key: "$exists", Value: false}}},
		{Key: "started_at", Value: bson.D{{Key: "$lt", Value: before}}},
	}
	cursor, err := col.Find(ctx, filter)
	if err != nil {
		return nil, apperr.Internal(err.Error())
	}
	var sessions []liveroom.Session
	if err := cursor.All(ctx, &sessions); err != nil {
		return nil, apperr.Internal(err.Error())
	}
	return sessions, nil
}

func (r *MongoLiveSessionRepository) EndCreatingSession(ctx context.Context, sessionID string, endedAt time.Time) (bool, error) {
	col := r.db.Collection(liveroom.CollectionSessions)
	filter := bson.D{
		{Key: "session_id", Value: sessionID},
		{Key: "status", Value: liveroom.StatusActive},
		{Key: "started_event_sent_at", Value: bson.D{{Key: "$exists", Value: false}}},
	}
	update := bson.D{{Key: "$set", Value: bson.D{
		{Key: "status", Value: liveroom.StatusEnded},
		{Key: "ended_at", Value: endedAt},
	}}}
	result, err := col.UpdateOne(ctx, filter, update)
	if err != nil {
		return false, apperr.Internal(err.Error())
	}
	return result.ModifiedCount == 1, nil
}

func (r *MongoLiveSessionRepository) MarkSessionStartedAndEnqueue(
	ctx context.Context,
	sessionID string,
	startedAt time.Time,
	event any,
) (bool, error) {
	message, err := newOutboxMessage(sessionID, event, startedAt)
	if err != nil {
		return false, apperr.Internal(err.Error())
	}
	col := r.db.Collection(liveroom.CollectionSessions)
	filter := bson.D{
		{Key: "session_id", Value: sessionID},
		{Key: "status", Value: liveroom.StatusActive},
		{Key: "started_event_sent_at", Value: bson.D{{Key: "$exists", Value: false}}},
	}
	update := bson.D{
		{Key: "$set", Value: bson.D{
			{Key: "started_at", Value: startedAt},
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

func (r *MongoLiveSessionRepository) RecordViewerJoinedAndEnqueue(
	ctx context.Context,
	v *liveroom.Viewer,
	occurrenceID string,
	event any,
) (bool, error) {
	message, err := newOutboxMessage(v.SessionID, event, v.JoinedAt)
	if err != nil {
		return false, apperr.Internal(err.Error())
	}
	col := r.db.Collection(liveroom.CollectionViewers)
	document := bson.D{
		{Key: "session_id", Value: v.SessionID},
		{Key: "user_id", Value: v.UserID},
		{Key: "channel_id", Value: v.ChannelID},
		{Key: "occurrence_id", Value: occurrenceID},
		{Key: "joined_at", Value: v.JoinedAt},
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

func (r *MongoLiveSessionRepository) MarkViewerLeftAndEnqueue(
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
	col := r.db.Collection(liveroom.CollectionViewers)
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

func (r *MongoLiveSessionRepository) CleanupOrphanViewers(ctx context.Context, sessionID string, now time.Time) (int64, error) {
	col := r.db.Collection(liveroom.CollectionViewers)
	filter := bson.D{
		{Key: "session_id", Value: sessionID},
		{Key: "left_at", Value: nil},
	}
	update := bson.D{{Key: "$set", Value: bson.D{{Key: "left_at", Value: now}}}}
	result, err := col.UpdateMany(ctx, filter, update)
	if err != nil {
		return 0, apperr.Internal(err.Error())
	}
	if _, err := r.db.Collection(liveroom.CollectionSessions).UpdateOne(
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

func (r *MongoLiveSessionRepository) GetViewerJoinedAt(
	ctx context.Context,
	sessionID string,
	userID int64,
	occurrenceID string,
) (*time.Time, error) {
	col := r.db.Collection(liveroom.CollectionViewers)
	filter := bson.D{
		{Key: "session_id", Value: sessionID},
		{Key: "user_id", Value: userID},
		{Key: "left_at", Value: nil},
	}
	if occurrenceID != "" {
		filter = append(filter, bson.E{Key: "occurrence_id", Value: occurrenceID})
	}
	var v liveroom.Viewer
	err := col.FindOne(ctx, filter).Decode(&v)
	if errors.Is(err, mongo.ErrNoDocuments) {
		return nil, nil
	}
	if err != nil {
		return nil, apperr.Internal(err.Error())
	}
	return &v.JoinedAt, nil
}

func (r *MongoLiveSessionRepository) CountActiveViewers(ctx context.Context, sessionID string) (int, error) {
	col := r.db.Collection(liveroom.CollectionViewers)
	filter := bson.D{
		{Key: "session_id", Value: sessionID},
		{Key: "left_at", Value: nil},
	}
	count, err := col.CountDocuments(ctx, filter)
	if err != nil {
		return 0, apperr.Internal(err.Error())
	}
	return int(count), nil
}

func (r *MongoLiveSessionRepository) EndSessionAndEnqueue(
	ctx context.Context,
	sessionID string,
	endedAt time.Time,
	event any,
	enqueueOnlyIfStarted bool,
) (bool, error) {
	message, err := newOutboxMessage(sessionID, event, endedAt)
	if err != nil {
		return false, apperr.Internal(err.Error())
	}
	set := bson.D{
		{Key: "status", Value: liveroom.StatusEnded},
		{Key: "ended_at", Value: endedAt},
		{Key: "cleanup_pending", Value: true},
	}
	var update any = bson.D{
		{Key: "$set", Value: set},
		{Key: "$push", Value: bson.D{{Key: outboxField, Value: message}}},
	}
	if enqueueOnlyIfStarted {
		set = append(set, bson.E{
			Key: outboxField,
			Value: bson.D{{Key: "$cond", Value: bson.A{
				bson.D{{Key: "$ne", Value: bson.A{bson.D{{Key: "$type", Value: "$started_event_sent_at"}}, "missing"}}},
				bson.D{{Key: "$concatArrays", Value: bson.A{
					bson.D{{Key: "$ifNull", Value: bson.A{"$" + outboxField, bson.A{}}}},
					bson.A{message},
				}}},
				bson.D{{Key: "$ifNull", Value: bson.A{"$" + outboxField, bson.A{}}}},
			}}},
		})
		update = mongo.Pipeline{bson.D{{Key: "$set", Value: set}}}
	}
	result, err := r.db.Collection(liveroom.CollectionSessions).UpdateOne(
		ctx,
		bson.D{{Key: "session_id", Value: sessionID}, {Key: "status", Value: liveroom.StatusActive}},
		update,
	)
	if err != nil {
		return false, apperr.Internal(err.Error())
	}
	return result.ModifiedCount == 1, nil
}

// ensure interface compliance
var _ liveroom.Repository = (*MongoLiveSessionRepository)(nil)
var _ liveroom.StartRecoveryRepository = (*MongoLiveSessionRepository)(nil)
