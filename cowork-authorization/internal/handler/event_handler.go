package handler

import (
	"errors"
	"io"
	"log"
	"net/http"

	"github.com/gin-gonic/gin"
	"github.com/tmp-it-play/datagsm-sdk-go/event"

	"github.com/cowork/authorization/internal/domain"
	"github.com/cowork/authorization/internal/monitoring"
	"github.com/cowork/authorization/internal/service"
)

const maxWebhookBodyBytes = 1 << 20

type WebhookReceipt struct {
	Status string `json:"status" binding:"required"`
}

type EventHandler struct{ eventSvc *service.EventService }

func NewEventHandler(eventSvc *service.EventService) *EventHandler {
	return &EventHandler{eventSvc: eventSvc}
}

func (h *EventHandler) DataGSMWebhook(c *gin.Context) {
	body, err := io.ReadAll(io.LimitReader(c.Request.Body, maxWebhookBodyBytes+1))
	if err != nil {
		webhookError(c, http.StatusBadRequest, "invalid_payload", "invalid")
		return
	}
	if len(body) > maxWebhookBodyBytes {
		webhookError(c, http.StatusRequestEntityTooLarge, "payload_too_large", "invalid")
		return
	}
	if len(c.Request.Header.Values(event.SignatureHeader)) != 1 {
		webhookError(c, http.StatusUnauthorized, "invalid_signature", "invalid")
		return
	}
	result, err := h.eventSvc.ProcessEvent(c.Request.Context(), body, c.GetHeader(event.SignatureHeader))
	if err != nil {
		switch {
		case errors.Is(err, event.ErrInvalidSignature):
			webhookError(c, http.StatusUnauthorized, "invalid_signature", "invalid")
		case errors.Is(err, domain.ErrInvalidWebhookPayload):
			webhookError(c, http.StatusBadRequest, "invalid_payload", "invalid")
		case errors.Is(err, domain.ErrWebhookExpired):
			webhookError(c, http.StatusBadRequest, "event_expired", "expired")
		case errors.Is(err, domain.ErrWebhookFuture):
			webhookError(c, http.StatusBadRequest, "invalid_event_timestamp", "invalid")
		case errors.Is(err, domain.ErrWebhookConflict):
			webhookError(c, http.StatusConflict, "event_id_conflict", "conflict")
		default:
			// SQL errors may contain event identities or student data.
			log.Println("Failed to persist webhook batch")
			webhookError(c, http.StatusServiceUnavailable, "temporarily_unavailable", "unavailable")
		}
		return
	}
	monitoring.RecordWebhookResult(string(result))
	c.JSON(http.StatusOK, gin.H{"status": result})
}

func webhookError(c *gin.Context, status int, code, metricResult string) {
	monitoring.RecordWebhookResult(metricResult)
	c.JSON(status, gin.H{"error": code})
}
