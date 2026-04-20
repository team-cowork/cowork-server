package webhook

import (
	"context"
	"log/slog"
	"net/http"
	"strconv"
	"time"

	"github.com/livekit/protocol/auth"
	livekit "github.com/livekit/protocol/livekit"
	lkwebhook "github.com/livekit/protocol/webhook"
	"go.mongodb.org/mongo-driver/v2/mongo"

	"github.com/cowork/cowork-voice/internal/apperror"
	"github.com/cowork/cowork-voice/internal/config"
	sessiondomain "github.com/cowork/cowork-voice/internal/domain/session"
	kafkadomain "github.com/cowork/cowork-voice/internal/infra/kafka"
)

type Handler struct {
	db           *mongo.Database
	kafka        *kafkadomain.Producer
	cfg          *config.AppConfig
	authProvider auth.KeyProvider
}

func NewHandler(db *mongo.Database, kp *kafkadomain.Producer, cfg *config.AppConfig) *Handler {
	return &Handler{
		db:           db,
		kafka:        kp,
		cfg:          cfg,
		authProvider: auth.NewSimpleKeyProvider(cfg.LiveKitAPIKey, cfg.LiveKitAPISecret),
	}
}

// Handle godoc
// @Summary      LiveKit 웹훅 수신
// @Description  LiveKit 서버가 전송하는 participant_joined / participant_left / room_finished 이벤트를 처리합니다. LiveKit 서버 내부 호출용이며 직접 호출하지 않습니다.
// @Tags         webhook
// @Accept       json
// @Param        Authorization  header  string  true  "LiveKit 웹훅 서명"
// @Success      200
// @Failure      401
// @Router       /voice/webhook [post]
func (h *Handler) Handle(w http.ResponseWriter, r *http.Request) {
	event, err := lkwebhook.ReceiveWebhookEvent(r, h.authProvider)
	if err != nil {
		apperror.WriteResponse(w, apperror.Unauthorized())
		return
	}

	ctx := r.Context()
	now := time.Now().UTC()
	nowStr := now.Format(time.RFC3339)

	switch event.GetEvent() {
	case "participant_joined":
		h.handleParticipantJoined(ctx, event, now, nowStr)
	case "participant_left":
		h.handleParticipantLeft(ctx, event, now, nowStr)
	case "room_finished":
		h.handleRoomFinished(ctx, event, now, nowStr)
	}

	w.WriteHeader(http.StatusOK)
}

func (h *Handler) handleParticipantJoined(ctx context.Context, event *livekit.WebhookEvent, now time.Time, nowStr string) {
	participant := event.GetParticipant()
	if participant == nil {
		return
	}
	room := event.GetRoom()
	if room == nil {
		return
	}

	userID, err := strconv.ParseInt(participant.Identity, 10, 64)
	if err != nil {
		return
	}
	channelID, ok := sessiondomain.ParseRoomName(room.Name)
	if !ok {
		return
	}

	voiceSession, err := sessiondomain.FindActiveSession(ctx, h.db, channelID)
	if err != nil || voiceSession == nil {
		slog.Warn("participant_joined: active session not found", "channel_id", channelID)
		return
	}

	firstStart, err := sessiondomain.MarkSessionStarted(ctx, h.db, voiceSession.SessionID, now)
	if err != nil {
		slog.Error("failed to mark session started", "err", err, "session_id", voiceSession.SessionID)
		return
	}
	if firstStart {
		if err := h.kafka.Publish(ctx, voiceSession.SessionID, &kafkadomain.SessionStartedEvent{
			EventType: kafkadomain.EventSessionStarted,
			SessionID: voiceSession.SessionID,
			ChannelID: channelID,
			TeamID:    voiceSession.TeamID,
			UserID:    userID,
			Timestamp: nowStr,
		}); err != nil {
			slog.Error("failed to publish SESSION_STARTED", "err", err)
		}
	}

	if err := h.kafka.Publish(ctx, voiceSession.SessionID, &kafkadomain.UserJoinedEvent{
		EventType: kafkadomain.EventUserJoined,
		SessionID: voiceSession.SessionID,
		ChannelID: channelID,
		TeamID:    voiceSession.TeamID,
		UserID:    userID,
		Timestamp: nowStr,
	}); err != nil {
		slog.Error("failed to publish USER_JOINED", "err", err)
	}
}

func (h *Handler) handleParticipantLeft(ctx context.Context, event *livekit.WebhookEvent, now time.Time, nowStr string) {
	participant := event.GetParticipant()
	if participant == nil {
		return
	}
	room := event.GetRoom()
	if room == nil {
		return
	}

	userID, err := strconv.ParseInt(participant.Identity, 10, 64)
	if err != nil {
		return
	}
	channelID, ok := sessiondomain.ParseRoomName(room.Name)
	if !ok {
		return
	}

	// room_finished가 먼저 도착한 경우에도 조회 가능하도록 status 무관 조회
	voiceSession, err := sessiondomain.FindLatestSessionByChannel(ctx, h.db, channelID)
	if err != nil || voiceSession == nil {
		slog.Warn("participant_left: session not found", "channel_id", channelID)
		return
	}

	joinedAt, err := sessiondomain.GetParticipantJoinedAt(ctx, h.db, voiceSession.SessionID, userID)
	if err != nil {
		slog.Error("failed to get participant joined_at", "err", err)
	}

	firstLeave, err := sessiondomain.MarkParticipantLeft(ctx, h.db, voiceSession.SessionID, userID, now)
	if err != nil {
		slog.Error("failed to mark participant left", "err", err)
		return
	}
	if !firstLeave {
		return
	}

	var durationSeconds int64
	if joinedAt != nil {
		durationSeconds = int64(now.Sub(*joinedAt).Seconds())
	}

	if err := h.kafka.Publish(ctx, voiceSession.SessionID, &kafkadomain.UserLeftEvent{
		EventType:       kafkadomain.EventUserLeft,
		SessionID:       voiceSession.SessionID,
		ChannelID:       channelID,
		TeamID:          voiceSession.TeamID,
		UserID:          userID,
		DurationSeconds: durationSeconds,
		Timestamp:       nowStr,
	}); err != nil {
		slog.Error("failed to publish USER_LEFT", "err", err)
	}
}

func (h *Handler) handleRoomFinished(ctx context.Context, event *livekit.WebhookEvent, now time.Time, nowStr string) {
	room := event.GetRoom()
	if room == nil {
		return
	}

	channelID, ok := sessiondomain.ParseRoomName(room.Name)
	if !ok {
		return
	}

	voiceSession, err := sessiondomain.FindActiveSession(ctx, h.db, channelID)
	if err != nil || voiceSession == nil {
		return
	}

	if err := sessiondomain.EndSession(ctx, h.db, voiceSession.SessionID, now); err != nil {
		slog.Error("failed to end session", "err", err, "session_id", voiceSession.SessionID)
		return
	}

	count, err := sessiondomain.CleanupOrphanParticipants(ctx, h.db, voiceSession.SessionID, now)
	if err != nil {
		slog.Error("failed to cleanup orphan participants", "err", err)
	} else if count > 0 {
		slog.Info("orphan participants cleaned up", "session_id", voiceSession.SessionID, "count", count)
	}

	durationSeconds := int64(now.Sub(voiceSession.StartedAt).Seconds())

	if err := h.kafka.Publish(ctx, voiceSession.SessionID, &kafkadomain.SessionEndedEvent{
		EventType:       kafkadomain.EventSessionEnded,
		SessionID:       voiceSession.SessionID,
		ChannelID:       channelID,
		TeamID:          voiceSession.TeamID,
		DurationSeconds: durationSeconds,
		Timestamp:       nowStr,
	}); err != nil {
		slog.Error("failed to publish SESSION_ENDED", "err", err)
	}
}
