package handler

import (
	"errors"
	"io"
	"log"
	"net/http"

	"github.com/cowork/authorization/internal/domain"
	"github.com/cowork/authorization/internal/monitoring"
	"github.com/cowork/authorization/internal/service"
	"github.com/gin-gonic/gin"
)

const maxWebhookBodyBytes = 1 << 20

type EventHandler struct{ eventSvc *service.EventService }

func NewEventHandler(eventSvc *service.EventService) *EventHandler {
	return &EventHandler{eventSvc: eventSvc}
}

// DataGSMWebhook godoc
// @Summary      DataGSM webhook 영속 접수
// @Description  서명을 검증한 student.updated 배치의 inbox와 전체 outbox를 같은 DB 트랜잭션으로 저장합니다. 200은 접수 완료이며 Kafka 발행 및 학생 정보 반영은 비동기입니다. 동일 ID·동일 내용은 duplicate, 내용 충돌은 409입니다. 발생 후 30일 이상 지난 이벤트와 5분 초과 미래 이벤트는 거부합니다. 기록은 30일 보관하며 미발행 작업이 남으면 보존합니다.
// @Tags         events
// @Accept       json
// @Produce      json
// @Param        X-DataGSM-Signature  header  string  true  "sha256=<HMAC-SHA256(secret, body)>"
// @Success      200  {object}  map[string]string  "accepted | duplicate | ignored"
// @Failure      400  {object}  map[string]string  "invalid_payload | event_expired | invalid_event_timestamp"
// @Failure      401  {object}  map[string]string  "invalid_signature"
// @Failure      409  {object}  map[string]string  "event_id_conflict"
// @Failure      413  {object}  map[string]string  "payload_too_large"
// @Failure      503  {object}  map[string]string  "temporarily_unavailable | webhook_not_configured"
// @Router       /events/datagsm [post]
func (h *EventHandler) DataGSMWebhook(c *gin.Context) {
	if !h.eventSvc.SecretConfigured() {
		webhookError(c, http.StatusServiceUnavailable, "webhook_not_configured", "unavailable")
		return
	}
	body, err := io.ReadAll(io.LimitReader(c.Request.Body, maxWebhookBodyBytes+1))
	if err != nil {
		webhookError(c, http.StatusBadRequest, "invalid_payload", "invalid")
		return
	}
	if len(body) > maxWebhookBodyBytes {
		webhookError(c, http.StatusRequestEntityTooLarge, "payload_too_large", "invalid")
		return
	}
	if !h.eventSvc.VerifySignature(body, c.GetHeader("X-DataGSM-Signature")) {
		webhookError(c, http.StatusUnauthorized, "invalid_signature", "invalid")
		return
	}
	result, err := h.eventSvc.ProcessEvent(c.Request.Context(), body)
	if err != nil {
		switch {
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
