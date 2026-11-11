package handler

import (
	"errors"
	"io"
	"log"
	"net/http"

	_ "github.com/cowork/authorization/internal/apidoc" // Gateway response schemas for swag.
	"github.com/cowork/authorization/internal/domain"
	"github.com/cowork/authorization/internal/monitoring"
	"github.com/cowork/authorization/internal/service"
	"github.com/gin-gonic/gin"
	"github.com/tmp-it-play/datagsm-sdk-go/event"
)

const maxWebhookBodyBytes = 1 << 20

type WebhookReceipt struct {
	Status string `json:"status" enums:"accepted,duplicate,ignored" binding:"required"`
}

type EventHandler struct{ eventSvc *service.EventService }

func NewEventHandler(eventSvc *service.EventService) *EventHandler {
	return &EventHandler{eventSvc: eventSvc}
}

// DataGSMWebhook godoc
// @Summary      DataGSM 웹훅 접수
// @Description  서명한 이벤트 본문을 전송합니다(최대 1 MiB). 200은 접수를 뜻하며 학생 정보는 나중에 반영됩니다. 같은 ID와 내용은 중복으로 처리하고 내용이 다르면 충돌로 처리합니다. 발생한 지 30일 이상 지났거나 현재보다 5분을 초과해 앞선 이벤트는 처리하지 않습니다.
// @Tags         events
// @Accept       json
// @Produce      json
// @Param        X-DataGSM-Signature  header  string  true  "sha256=<HMAC-SHA256(secret, body)>"
// @Param        body  body  object{id=string,event=string,timestamp=string,data=object}  true  "이벤트 ID, 종류, RFC 3339 발생 시각 및 데이터. data.old와 data.new는 같은 index로 변경 전후를 짝지으며 학생의 new.object는 비어 있을 수 없습니다."
// @Success      200  {object}  apidoc.Success[WebhookReceipt]  "접수 상태: accepted, duplicate 또는 ignored"
// @Failure      400  {object}  apidoc.Error  "잘못된 이벤트 형식 또는 발생 시각"
// @Failure      401  {object}  apidoc.Error  "웹훅 서명 검증 실패"
// @Failure      409  {object}  apidoc.Error  "같은 이벤트 ID의 내용 불일치"
// @Failure      413  {object}  apidoc.Error  "본문 크기 제한 초과"
// @Failure      503  {object}  apidoc.Error  "웹훅 접수 서비스 사용 불가"
// @Router       /events/datagsm [post]
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
