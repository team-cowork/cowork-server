package redis

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"time"

	"github.com/redis/go-redis/v9"

	room "github.com/cowork/cowork-voice/internal/domain/voice_room"
)

const sessionTTL = 48 * time.Hour

type cachedSessionRepository struct {
	mongo room.Repository
	rdb   *redis.Client
}

func NewCachedSessionRepository(mongo room.Repository, rdb *redis.Client) room.Repository {
	return &cachedSessionRepository{mongo: mongo, rdb: rdb}
}

func channelKey(channelID int64) string  { return fmt.Sprintf("voice:active:channel:%d", channelID) }
func roomKey(roomName string) string     { return fmt.Sprintf("voice:active:room:%s", roomName) }
func sessionKey(sessionID string) string { return fmt.Sprintf("voice:active:session:%s", sessionID) }

func (r *cachedSessionRepository) cacheSession(ctx context.Context, s *room.VoiceSession) {
	data, err := json.Marshal(s)
	if err != nil {
		slog.Warn("redis: failed to marshal session", "err", err, "session_id", s.SessionID)
		return
	}
	pipe := r.rdb.Pipeline()
	pipe.Set(ctx, channelKey(s.ChannelID), data, sessionTTL)
	pipe.Set(ctx, roomKey(s.RoomName), data, sessionTTL)
	pipe.Set(ctx, sessionKey(s.SessionID), data, sessionTTL)
	if _, err := pipe.Exec(ctx); err != nil {
		slog.Warn("redis: failed to cache session", "err", err, "session_id", s.SessionID)
	}
}

func (r *cachedSessionRepository) evictSession(ctx context.Context, s *room.VoiceSession) {
	pipe := r.rdb.Pipeline()
	pipe.Del(ctx, channelKey(s.ChannelID))
	pipe.Del(ctx, roomKey(s.RoomName))
	pipe.Del(ctx, sessionKey(s.SessionID))
	if _, err := pipe.Exec(ctx); err != nil {
		slog.Warn("redis: failed to evict session", "err", err, "session_id", s.SessionID)
	}
}

func (r *cachedSessionRepository) getFromCache(ctx context.Context, key string) (*room.VoiceSession, error) {
	data, err := r.rdb.Get(ctx, key).Result()
	if err == redis.Nil {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	var s room.VoiceSession
	if err := json.Unmarshal([]byte(data), &s); err != nil {
		return nil, err
	}
	return &s, nil
}

func (r *cachedSessionRepository) FindActiveSession(ctx context.Context, channelID int64) (*room.VoiceSession, error) {
	s, err := r.getFromCache(ctx, channelKey(channelID))
	if err != nil {
		slog.Warn("redis: FindActiveSession cache error, falling back to mongo", "err", err, "channel_id", channelID)
	} else if s != nil {
		return s, nil
	}

	s, err = r.mongo.FindActiveSession(ctx, channelID)
	if err != nil {
		return nil, err
	}
	if s != nil {
		r.cacheSession(ctx, s)
	}
	return s, nil
}

func (r *cachedSessionRepository) FindSessionByRoomName(ctx context.Context, roomName string) (*room.VoiceSession, error) {
	s, err := r.getFromCache(ctx, roomKey(roomName))
	if err != nil {
		slog.Warn("redis: FindSessionByRoomName cache error, falling back to mongo", "err", err, "room_name", roomName)
	} else if s != nil {
		return s, nil
	}

	s, err = r.mongo.FindSessionByRoomName(ctx, roomName)
	if err != nil {
		return nil, err
	}
	if s != nil && s.Status == room.StatusActive {
		r.cacheSession(ctx, s)
	}
	return s, nil
}

func (r *cachedSessionRepository) CreateSession(ctx context.Context, channelID, teamID int64) (*room.VoiceSession, error) {
	s, err := r.mongo.CreateSession(ctx, channelID, teamID)
	if err != nil {
		return nil, err
	}
	if s != nil {
		r.cacheSession(ctx, s)
	}
	return s, nil
}

func (r *cachedSessionRepository) GetSession(ctx context.Context, sessionID string) (*room.VoiceSession, error) {
	return r.mongo.GetSession(ctx, sessionID)
}

func (r *cachedSessionRepository) EndSession(ctx context.Context, sessionID string, endedAt time.Time) error {
	s, err := r.getFromCache(ctx, sessionKey(sessionID))
	if err != nil {
		slog.Warn("redis: EndSession cache lookup failed", "err", err, "session_id", sessionID)
	}

	if s == nil {
		if ms, err := r.mongo.GetSession(ctx, sessionID); err == nil && ms != nil {
			s = ms
		}
	}

	if err := r.mongo.EndSession(ctx, sessionID, endedAt); err != nil {
		return err
	}

	if s != nil {
		r.evictSession(ctx, s)
	}
	return nil
}

func (r *cachedSessionRepository) MarkSessionStarted(ctx context.Context, sessionID string, startedAt time.Time) (bool, error) {
	updated, err := r.mongo.MarkSessionStarted(ctx, sessionID, startedAt)
	if err != nil {
		return false, err
	}
	if !updated {
		return false, nil
	}

	s, cacheErr := r.getFromCache(ctx, sessionKey(sessionID))
	if cacheErr == nil && s != nil {
		s.StartedAt = startedAt
		s.StartedEventSentAt = &startedAt
		r.cacheSession(ctx, s)
	}
	return true, nil
}

func (r *cachedSessionRepository) InsertParticipant(ctx context.Context, p *room.VoiceParticipant) error {
	return r.mongo.InsertParticipant(ctx, p)
}

func (r *cachedSessionRepository) MarkParticipantLeft(ctx context.Context, sessionID string, userID int64, now time.Time) (bool, error) {
	return r.mongo.MarkParticipantLeft(ctx, sessionID, userID, now)
}

func (r *cachedSessionRepository) CleanupOrphanParticipants(ctx context.Context, sessionID string, now time.Time) (int64, error) {
	return r.mongo.CleanupOrphanParticipants(ctx, sessionID, now)
}

func (r *cachedSessionRepository) GetParticipantJoinedAt(ctx context.Context, sessionID string, userID int64) (*time.Time, error) {
	return r.mongo.GetParticipantJoinedAt(ctx, sessionID, userID)
}

// Ping verifies connectivity to Redis.
func Ping(ctx context.Context, rdb *redis.Client) error {
	if err := rdb.Ping(ctx).Err(); err != nil {
		return fmt.Errorf("redis ping failed: %w", err)
	}
	return nil
}

// NewClient creates a Redis client from the given address and password.
func NewClient(addr, password string, db int) *redis.Client {
	return redis.NewClient(&redis.Options{
		Addr:     addr,
		Password: password,
		DB:       db,
	})
}

// ensure interface compliance
var _ room.Repository = (*cachedSessionRepository)(nil)
