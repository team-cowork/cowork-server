package session

import (
	"encoding/json"
	"fmt"
	"log/slog"
	"net/http"
	"strconv"
	"time"

	"github.com/go-chi/chi/v5"
	lksdk "github.com/livekit/server-sdk-go/v2"
	"go.mongodb.org/mongo-driver/v2/mongo"

	"github.com/cowork/cowork-voice/internal/apperror"
	"github.com/cowork/cowork-voice/internal/config"
	"github.com/cowork/cowork-voice/internal/domain/channel"
	lkdomain "github.com/cowork/cowork-voice/internal/domain/livekit"
	"github.com/cowork/cowork-voice/internal/dto"
	"github.com/cowork/cowork-voice/internal/middleware"
)

type Handler struct {
	db            *mongo.Database
	channelClient *channel.Client
	livekitClient *lksdk.RoomServiceClient
	cfg           *config.AppConfig
}

func NewHandler(db *mongo.Database, cc *channel.Client, lk *lksdk.RoomServiceClient, cfg *config.AppConfig) *Handler {
	return &Handler{db: db, channelClient: cc, livekitClient: lk, cfg: cfg}
}

func (h *Handler) Join(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	userID := middleware.GetUserID(ctx)

	channelID, err := parseChannelIDParam(r)
	if err != nil {
		apperror.WriteResponse(w, apperror.Internal("invalid channel_id"))
		return
	}

	teamID, appErr := h.channelClient.VerifyMembership(ctx, channelID, userID)
	if appErr != nil {
		apperror.WriteResponse(w, toAppError(appErr))
		return
	}

	voiceSession, err := FindActiveSession(ctx, h.db, channelID)
	if err != nil {
		apperror.WriteResponse(w, toAppError(err))
		return
	}
	if voiceSession == nil {
		voiceSession, err = CreateSession(ctx, h.db, channelID, teamID)
		if err != nil {
			apperror.WriteResponse(w, toAppError(err))
			return
		}
	}

	if err := lkdomain.CreateRoomIfNotExists(ctx, h.livekitClient, voiceSession.RoomName); err != nil {
		apperror.WriteResponse(w, toAppError(err))
		return
	}

	if err := InsertParticipant(ctx, h.db, &VoiceParticipant{
		SessionID: voiceSession.SessionID,
		UserID:    userID,
		ChannelID: channelID,
		JoinedAt:  time.Now().UTC(),
	}); err != nil {
		apperror.WriteResponse(w, toAppError(err))
		return
	}

	token, err := lkdomain.GenerateToken(h.cfg.LiveKitAPIKey, h.cfg.LiveKitAPISecret, userID, voiceSession.RoomName, h.cfg.LiveKitTokenTTLSecs)
	if err != nil {
		apperror.WriteResponse(w, toAppError(err))
		return
	}

	writeJSON(w, http.StatusOK, dto.JoinResponse{
		Token:      token,
		LiveKitURL: h.cfg.LiveKitWsURL,
		SessionID:  voiceSession.SessionID,
		RoomName:   voiceSession.RoomName,
	})
}

func (h *Handler) Leave(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	userID := middleware.GetUserID(ctx)

	channelID, err := parseChannelIDParam(r)
	if err != nil {
		apperror.WriteResponse(w, apperror.Internal("invalid channel_id"))
		return
	}

	if _, appErr := h.channelClient.VerifyMembership(ctx, channelID, userID); appErr != nil {
		apperror.WriteResponse(w, toAppError(appErr))
		return
	}

	voiceSession, err := FindActiveSession(ctx, h.db, channelID)
	if err != nil {
		apperror.WriteResponse(w, toAppError(err))
		return
	}
	if voiceSession == nil {
		apperror.WriteResponse(w, apperror.NotFound("active session not found"))
		return
	}

	identity := strconv.FormatInt(userID, 10)
	if err := lkdomain.RemoveParticipant(ctx, h.livekitClient, voiceSession.RoomName, identity); err != nil {
		apperror.WriteResponse(w, toAppError(err))
		return
	}

	participants, err := lkdomain.ListParticipants(ctx, h.livekitClient, voiceSession.RoomName)
	if err != nil {
		slog.Warn("failed to list participants after leave", "err", err)
	} else if len(participants) == 0 {
		if err := lkdomain.DeleteRoom(ctx, h.livekitClient, voiceSession.RoomName); err != nil {
			slog.Warn("failed to delete empty room", "err", err, "room", voiceSession.RoomName)
		}
	}

	w.WriteHeader(http.StatusNoContent)
}

func (h *Handler) Participants(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	userID := middleware.GetUserID(ctx)

	channelID, err := parseChannelIDParam(r)
	if err != nil {
		apperror.WriteResponse(w, apperror.Internal("invalid channel_id"))
		return
	}

	if _, appErr := h.channelClient.VerifyMembership(ctx, channelID, userID); appErr != nil {
		apperror.WriteResponse(w, toAppError(appErr))
		return
	}

	roomName := fmt.Sprintf("voice-%d", channelID)
	lkParticipants, err := lkdomain.ListParticipants(ctx, h.livekitClient, roomName)
	if err != nil {
		apperror.WriteResponse(w, toAppError(err))
		return
	}

	participants := make([]dto.ParticipantInfo, 0, len(lkParticipants))
	for _, p := range lkParticipants {
		uid, err := strconv.ParseInt(p.Identity, 10, 64)
		if err != nil {
			continue
		}
		joinedAt := time.Unix(p.JoinedAt, 0).UTC().Format(time.RFC3339)
		participants = append(participants, dto.ParticipantInfo{
			UserID:   uid,
			JoinedAt: joinedAt,
		})
	}

	writeJSON(w, http.StatusOK, dto.ParticipantsResponse{
		ChannelID:    channelID,
		RoomName:     roomName,
		Participants: participants,
	})
}

func (h *Handler) GetSession(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	userID := middleware.GetUserID(ctx)
	sessionID := chi.URLParam(r, "session_id")

	s, err := GetSession(ctx, h.db, sessionID)
	if err != nil {
		apperror.WriteResponse(w, toAppError(err))
		return
	}
	if s == nil {
		apperror.WriteResponse(w, apperror.NotFound("session not found"))
		return
	}

	if _, appErr := h.channelClient.VerifyMembership(ctx, s.ChannelID, userID); appErr != nil {
		apperror.WriteResponse(w, toAppError(appErr))
		return
	}

	var endedAt *string
	if s.EndedAt != nil {
		v := s.EndedAt.UTC().Format(time.RFC3339)
		endedAt = &v
	}

	writeJSON(w, http.StatusOK, dto.SessionResponse{
		SessionID: s.SessionID,
		ChannelID: s.ChannelID,
		TeamID:    s.TeamID,
		Status:    s.Status,
		StartedAt: s.StartedAt.UTC().Format(time.RFC3339),
		EndedAt:   endedAt,
	})
}

func parseChannelIDParam(r *http.Request) (int64, error) {
	return strconv.ParseInt(chi.URLParam(r, "channel_id"), 10, 64)
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(v)
}

func toAppError(err error) *apperror.AppError {
	if err == nil {
		return nil
	}
	if appErr, ok := err.(*apperror.AppError); ok {
		return appErr
	}
	return apperror.Internal(err.Error())
}
