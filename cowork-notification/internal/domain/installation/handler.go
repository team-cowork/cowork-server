package installation

import (
	"encoding/json"
	"net/http"

	"github.com/go-chi/chi/v5"

	"github.com/cowork/cowork-notification/internal/apperr"
	"github.com/cowork/cowork-notification/internal/middleware"
)

type Handler struct {
	svc InstallationService
}

func NewHandler(svc InstallationService) *Handler {
	return &Handler{svc: svc}
}

type registerInstallationRequest struct {
	FID      string `json:"fid"`
	Platform string `json:"platform"`
}

func (h *Handler) RegisterInstallation(w http.ResponseWriter, r *http.Request) {
	accountID, ok := middleware.AccountIDFromContext(r.Context())
	if !ok {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	var req registerInstallationRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}
	if req.FID == "" || req.Platform == "" {
		http.Error(w, "fid and platform are required", http.StatusBadRequest)
		return
	}
	if err := h.svc.RegisterInstallation(r.Context(), accountID, req.FID, req.Platform); err != nil {
		writeError(w, err)
		return
	}
	w.WriteHeader(http.StatusCreated)
}

func (h *Handler) DeleteInstallation(w http.ResponseWriter, r *http.Request) {
	accountID, ok := middleware.AccountIDFromContext(r.Context())
	if !ok {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	fid := chi.URLParam(r, "fid")
	if fid == "" {
		http.Error(w, "fid is required", http.StatusBadRequest)
		return
	}
	if err := h.svc.DeleteInstallation(r.Context(), accountID, fid); err != nil {
		writeError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func writeError(w http.ResponseWriter, err error) {
	if appErr, ok := err.(*apperr.AppError); ok {
		http.Error(w, appErr.Message, appErr.Code)
		return
	}
	http.Error(w, "internal server error", http.StatusInternalServerError)
}
