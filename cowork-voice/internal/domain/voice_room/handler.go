package room

import (
	"encoding/json"
	"net/http"
	"strconv"

	"github.com/go-chi/chi/v5"

	"github.com/cowork/cowork-voice/internal/apperr"
	"github.com/cowork/cowork-voice/internal/middleware"
)

type Handler struct {
	svc Service
}

func NewHandler(svc Service) *Handler {
	return &Handler{svc: svc}
}

func (h *Handler) Join(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	userID, ok := middleware.GetUserID(ctx)
	if !ok {
		apperr.WriteResponse(w, apperr.Unauthorized())
		return
	}

	channelID, err := parseChannelIDParam(r)
	if err != nil {
		apperr.WriteResponse(w, apperr.BadRequest("invalid channel_id"))
		return
	}

	resp, err := h.svc.Join(ctx, channelID, userID)
	if err != nil {
		apperr.WriteResponse(w, toAppError(err))
		return
	}

	writeJSON(w, http.StatusOK, resp)
}

func (h *Handler) Leave(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	userID, ok := middleware.GetUserID(ctx)
	if !ok {
		apperr.WriteResponse(w, apperr.Unauthorized())
		return
	}

	channelID, err := parseChannelIDParam(r)
	if err != nil {
		apperr.WriteResponse(w, apperr.BadRequest("invalid channel_id"))
		return
	}

	if err := h.svc.Leave(ctx, channelID, userID); err != nil {
		apperr.WriteResponse(w, toAppError(err))
		return
	}

	w.WriteHeader(http.StatusNoContent)
}

func (h *Handler) Participants(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	userID, ok := middleware.GetUserID(ctx)
	if !ok {
		apperr.WriteResponse(w, apperr.Unauthorized())
		return
	}

	channelID, err := parseChannelIDParam(r)
	if err != nil {
		apperr.WriteResponse(w, apperr.BadRequest("invalid channel_id"))
		return
	}

	resp, err := h.svc.GetParticipants(ctx, channelID, userID)
	if err != nil {
		apperr.WriteResponse(w, toAppError(err))
		return
	}

	writeJSON(w, http.StatusOK, resp)
}

func (h *Handler) GetSession(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	userID, ok := middleware.GetUserID(ctx)
	if !ok {
		apperr.WriteResponse(w, apperr.Unauthorized())
		return
	}

	sessionID := chi.URLParam(r, "session_id")

	resp, err := h.svc.GetSession(ctx, sessionID, userID)
	if err != nil {
		apperr.WriteResponse(w, toAppError(err))
		return
	}

	writeJSON(w, http.StatusOK, resp)
}

func parseChannelIDParam(r *http.Request) (int64, error) {
	return strconv.ParseInt(chi.URLParam(r, "channel_id"), 10, 64)
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(v)
}

func toAppError(err error) *apperr.Error {
	if err == nil {
		return nil
	}
	if appErr, ok := err.(*apperr.Error); ok {
		return appErr
	}
	return apperr.Internal(err.Error())
}
