package webhook

import (
	"context"
	"errors"
	"log/slog"
	"strconv"
	"time"

	livekit "github.com/livekit/protocol/livekit"

	livedomain "github.com/cowork/cowork-voice/internal/domain/live_room"
	kafkadomain "github.com/cowork/cowork-voice/internal/infra/kafka"
)

type LiveWebhookService struct {
	repo LiveSessionRepository
	room LiveRoomController
	now  func() time.Time
}

func NewLiveWebhookService(repo LiveSessionRepository, room LiveRoomController) *LiveWebhookService {
	return &LiveWebhookService{
		repo: repo,
		room: room,
		now: func() time.Time {
			return time.Now().UTC()
		},
	}
}

func (s *LiveWebhookService) HandleEvent(ctx context.Context, event *livekit.WebhookEvent) error {
	now := time.Now().UTC()
	if s.now != nil {
		now = s.now()
	}
	nowStr := now.Format(time.RFC3339)

	switch WebhookEventType(event.GetEvent()) {
	case EventParticipantJoined:
		return s.handleParticipantJoined(ctx, event, now, nowStr)
	case EventParticipantLeft:
		return s.handleParticipantLeft(ctx, event, now, nowStr)
	case EventRoomFinished:
		return s.handleRoomFinished(ctx, event, now, nowStr)
	}
	return nil
}

func (s *LiveWebhookService) handleParticipantJoined(ctx context.Context, event *livekit.WebhookEvent, now time.Time, nowStr string) error {
	participant := event.GetParticipant()
	if participant == nil {
		return nil
	}
	room := event.GetRoom()
	if room == nil {
		return nil
	}

	userID, err := strconv.ParseInt(participant.Identity, 10, 64)
	if err != nil {
		return nil
	}
	parsedRoom, ok := livedomain.ParseRoomName(room.Name)
	if !ok {
		return nil
	}

	liveSession, err := s.repo.FindSessionByRoomName(ctx, room.Name)
	if err != nil {
		slog.Error("live participant_joined: failed to find session", "err", err, "room_name", room.Name)
		return errors.New("internal server error")
	}
	if liveSession == nil {
		slog.Warn("live participant_joined: live session not found", "room_name", room.Name, "channel_id", parsedRoom.ChannelID)
		return nil
	}

	if userID == liveSession.HostUserID {
		// 호스트 첫 접속 = 방송 시작. 게이트가 재접속 시 LIVE_STARTED 재발행을 막는다.
		_, err := s.repo.MarkSessionStartedAndEnqueue(ctx, liveSession.SessionID, now, &kafkadomain.LiveStartedEvent{
			EventType:  kafkadomain.EventLiveStarted,
			SessionID:  liveSession.SessionID,
			ChannelID:  parsedRoom.ChannelID,
			TeamID:     liveSession.TeamID,
			HostUserID: liveSession.HostUserID,
			Timestamp:  nowStr,
		})
		if err != nil {
			slog.Error("failed to mark live session started and enqueue LIVE_STARTED", "err", err, "session_id", liveSession.SessionID)
			return err
		}
		return nil
	}

	_, err = s.repo.RecordViewerJoinedAndEnqueue(ctx, &livedomain.LiveViewer{
		SessionID: liveSession.SessionID,
		UserID:    userID,
		ChannelID: parsedRoom.ChannelID,
		JoinedAt:  now,
	}, participantOccurrenceID(event), &kafkadomain.ViewerJoinedEvent{
		EventType: kafkadomain.EventViewerJoined,
		SessionID: liveSession.SessionID,
		ChannelID: parsedRoom.ChannelID,
		TeamID:    liveSession.TeamID,
		UserID:    userID,
		Timestamp: nowStr,
	})
	if err != nil {
		slog.Error("failed to record viewer and enqueue VIEWER_JOINED", "err", err, "session_id", liveSession.SessionID)
		return err
	}
	return nil
}

func (s *LiveWebhookService) handleParticipantLeft(ctx context.Context, event *livekit.WebhookEvent, now time.Time, nowStr string) error {
	participant := event.GetParticipant()
	if participant == nil {
		return nil
	}
	room := event.GetRoom()
	if room == nil {
		return nil
	}

	userID, err := strconv.ParseInt(participant.Identity, 10, 64)
	if err != nil {
		return nil
	}
	parsedRoom, ok := livedomain.ParseRoomName(room.Name)
	if !ok {
		return nil
	}

	liveSession, err := s.repo.FindSessionByRoomName(ctx, room.Name)
	if err != nil {
		slog.Error("live participant_left: failed to find session", "err", err, "room_name", room.Name)
		return errors.New("internal server error")
	}
	if liveSession == nil {
		slog.Warn("live participant_left: live session not found", "room_name", room.Name, "channel_id", parsedRoom.ChannelID)
		return nil
	}

	if userID == liveSession.HostUserID {
		// 호스트 이탈 = 방송 종료. DeleteRoom이 시청자별 participant_left와
		// room_finished를 발생시켜 VIEWER_LEFT·LIVE_ENDED가 순서대로 발행된다.
		if err := s.room.DeleteRoom(ctx, room.Name); err != nil {
			slog.Error("failed to delete live room after host left", "err", err, "room_name", room.Name)
			return err
		}
		return nil
	}

	occurrenceID := participantOccurrenceID(event)
	joinedAt, err := s.repo.GetViewerJoinedAt(ctx, liveSession.SessionID, userID, occurrenceID)
	if err != nil {
		slog.Error("failed to get viewer joined_at", "err", err)
		return err
	}

	var durationSeconds int64
	if joinedAt != nil {
		durationSeconds = livedomain.DurationSecondsSince(now, *joinedAt)
	}

	_, err = s.repo.MarkViewerLeftAndEnqueue(ctx, liveSession.SessionID, userID, occurrenceID, now, &kafkadomain.ViewerLeftEvent{
		EventType:       kafkadomain.EventViewerLeft,
		SessionID:       liveSession.SessionID,
		ChannelID:       parsedRoom.ChannelID,
		TeamID:          liveSession.TeamID,
		UserID:          userID,
		DurationSeconds: durationSeconds,
		Timestamp:       nowStr,
	})
	if err != nil {
		slog.Error("failed to mark viewer left and enqueue VIEWER_LEFT", "err", err)
		return err
	}
	return nil
}

func (s *LiveWebhookService) handleRoomFinished(ctx context.Context, event *livekit.WebhookEvent, now time.Time, nowStr string) error {
	room := event.GetRoom()
	if room == nil {
		return nil
	}

	parsedRoom, ok := livedomain.ParseRoomName(room.Name)
	if !ok {
		return nil
	}

	liveSession, err := s.repo.FindSessionByRoomName(ctx, room.Name)
	if err != nil {
		slog.Error("live room_finished: failed to find session", "err", err, "room_name", room.Name)
		return errors.New("internal server error")
	}
	if liveSession == nil {
		return nil
	}

	durationSeconds := livedomain.DurationSecondsSince(now, liveSession.StartedAt)
	ended, err := s.repo.EndSessionAndEnqueue(ctx, liveSession.SessionID, now, &kafkadomain.LiveEndedEvent{
		EventType:       kafkadomain.EventLiveEnded,
		SessionID:       liveSession.SessionID,
		ChannelID:       parsedRoom.ChannelID,
		TeamID:          liveSession.TeamID,
		HostUserID:      liveSession.HostUserID,
		DurationSeconds: durationSeconds,
		Timestamp:       nowStr,
	}, true)
	if err != nil {
		slog.Error("failed to end live session", "err", err, "session_id", liveSession.SessionID)
		return err
	}
	if !ended {
		// 이미 종료된 세션(room_finished 재전송) → 정리·발행을 반복하지 않는다.
		return nil
	}

	count, err := s.repo.CleanupOrphanViewers(ctx, liveSession.SessionID, now)
	if err != nil {
		slog.Error("failed to cleanup orphan viewers", "err", err)
	} else if count > 0 {
		slog.Info("orphan viewers cleaned up", "session_id", liveSession.SessionID, "count", count)
	}

	return nil
}
