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

// sessionTTL은 무효화(eviction)가 실패했을 때 stale 항목이 살아남는 최대 시간을 제한하는 안전장치다.
// Mongo가 진실의 원천이므로 TTL 만료 후 cache miss가 나도 정상 동작(재조회 후 재캐싱)하며,
// room_finished 시점에 Redis가 일시 장애여서 evict가 실패하더라도 종료된 세션이 최대 이 시간까지만 남는다.
const sessionTTL = 2 * time.Hour

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
		// 무효화 실패 시 stale 항목은 sessionTTL까지 남는다(안전장치). 운영 알람을 위해 Error로 남긴다.
		slog.Error("redis: failed to evict session, stale entry will expire via TTL", "err", err, "session_id", s.SessionID, "ttl", sessionTTL.String())
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
	if cached, err := r.getFromCache(ctx, channelKey(channelID)); err != nil {
		slog.Warn("redis: FindActiveSession cache error, falling back to mongo", "err", err, "channel_id", channelID)
	} else if cached != nil {
		if verified := r.verifyStillActive(ctx, cached); verified != nil {
			return verified, nil
		}
	}

	s, err := r.mongo.FindActiveSession(ctx, channelID)
	if err != nil {
		return nil, err
	}
	if s != nil {
		r.cacheSession(ctx, s)
	}
	return s, nil
}

func (r *cachedSessionRepository) FindSessionByRoomName(ctx context.Context, roomName string) (*room.VoiceSession, error) {
	if cached, err := r.getFromCache(ctx, roomKey(roomName)); err != nil {
		slog.Warn("redis: FindSessionByRoomName cache error, falling back to mongo", "err", err, "room_name", roomName)
	} else if cached != nil {
		if verified := r.verifyStillActive(ctx, cached); verified != nil {
			return verified, nil
		}
	}

	s, err := r.mongo.FindSessionByRoomName(ctx, roomName)
	if err != nil {
		return nil, err
	}
	if s != nil && s.Status == room.StatusActive {
		r.cacheSession(ctx, s)
	}
	return s, nil
}

// verifyStillActive는 cache hit을 후보로만 취급하고 MongoDB의 현재 상태로 재확인한다.
// eviction 실패나 지연된 write로 종료된 세션이 sessionTTL 동안 active로 반환되는 것을
// 막기 위한 것이다(docs/todo/items/35-reliability/voice-session-cache-staleness.md).
// cached session이 더 이상 active가 아니면 stale key를 지우고 nil을 반환해 호출부가
// MongoDB를 authoritative source로 다시 조회하게 한다.
//
// 매 cache hit마다 MongoDB round-trip이 추가되어 읽기 경로에서의 캐시 이점이 사실상
// 사라진다. 저비용 검증(generation/tombstone 비교)은 후속 작업으로 남겨둔다.
func (r *cachedSessionRepository) verifyStillActive(ctx context.Context, cached *room.VoiceSession) *room.VoiceSession {
	authoritative, err := r.mongo.GetSession(ctx, cached.SessionID)
	if err != nil {
		slog.Warn("redis: cache verification failed, falling back to mongo", "err", err, "session_id", cached.SessionID)
		return nil
	}
	if authoritative != nil && authoritative.Status == room.StatusActive {
		return authoritative
	}
	r.evictSession(ctx, cached)
	return nil
}

func (r *cachedSessionRepository) CreateSession(ctx context.Context, channelID, teamID int64) (*room.VoiceSession, bool, error) {
	s, created, err := r.mongo.CreateSession(ctx, channelID, teamID)
	if err != nil {
		return nil, false, err
	}
	if s != nil {
		r.cacheSession(ctx, s)
	}
	return s, created, nil
}

func (r *cachedSessionRepository) GetSession(ctx context.Context, sessionID string) (*room.VoiceSession, error) {
	return r.mongo.GetSession(ctx, sessionID)
}

func (r *cachedSessionRepository) EndSession(ctx context.Context, sessionID string, endedAt time.Time) (bool, error) {
	s, err := r.getFromCache(ctx, sessionKey(sessionID))
	if err != nil {
		slog.Warn("redis: EndSession cache lookup failed", "err", err, "session_id", sessionID)
	}

	if s == nil {
		if ms, err := r.mongo.GetSession(ctx, sessionID); err == nil && ms != nil {
			s = ms
		}
	}

	ended, err := r.mongo.EndSession(ctx, sessionID, endedAt)
	if err != nil {
		return false, err
	}

	if s != nil {
		r.evictSession(ctx, s)
	}
	return ended, nil
}

func (r *cachedSessionRepository) MarkSessionStartedAndEnqueue(
	ctx context.Context,
	sessionID string,
	startedAt time.Time,
	event any,
) (bool, error) {
	updated, err := r.mongo.MarkSessionStartedAndEnqueue(ctx, sessionID, startedAt, event)
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

func (r *cachedSessionRepository) RecordParticipantJoinedAndEnqueue(
	ctx context.Context,
	p *room.VoiceParticipant,
	occurrenceID string,
	event any,
) (bool, error) {
	return r.mongo.RecordParticipantJoinedAndEnqueue(ctx, p, occurrenceID, event)
}

func (r *cachedSessionRepository) MarkParticipantLeftAndEnqueue(
	ctx context.Context,
	sessionID string,
	userID int64,
	occurrenceID string,
	now time.Time,
	event any,
) (bool, error) {
	return r.mongo.MarkParticipantLeftAndEnqueue(ctx, sessionID, userID, occurrenceID, now, event)
}

func (r *cachedSessionRepository) CleanupOrphanParticipants(ctx context.Context, sessionID string, now time.Time) (int64, error) {
	return r.mongo.CleanupOrphanParticipants(ctx, sessionID, now)
}

func (r *cachedSessionRepository) GetParticipantJoinedAt(
	ctx context.Context,
	sessionID string,
	userID int64,
	occurrenceID string,
) (*time.Time, error) {
	return r.mongo.GetParticipantJoinedAt(ctx, sessionID, userID, occurrenceID)
}

func (r *cachedSessionRepository) EndSessionAndEnqueue(
	ctx context.Context,
	sessionID string,
	endedAt time.Time,
	event any,
) (bool, error) {
	s, err := r.getFromCache(ctx, sessionKey(sessionID))
	if err != nil {
		slog.Warn("redis: EndSessionAndEnqueue cache lookup failed", "err", err, "session_id", sessionID)
	}
	if s == nil {
		if stored, lookupErr := r.mongo.GetSession(ctx, sessionID); lookupErr == nil {
			s = stored
		}
	}
	ended, err := r.mongo.EndSessionAndEnqueue(ctx, sessionID, endedAt, event)
	if err != nil {
		return false, err
	}
	if ended && s != nil {
		r.evictSession(ctx, s)
	}
	return ended, nil
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
