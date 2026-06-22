package handler

import (
	"io"
	"log"
	"net/http"

	"github.com/cowork/authorization/internal/service"
	"github.com/gin-gonic/gin"
)

const maxWebhookBodyBytes = 1 << 20 // 1MB

type EventHandler struct {
	eventSvc *service.EventService
}

func NewEventHandler(eventSvc *service.EventService) *EventHandler {
	return &EventHandler{eventSvc: eventSvc}
}

// DataGSMWebhook godoc
// @Summary      DataGSM webhook 수신
// @Description  DataGSM이 전송하는 student 라이프사이클 이벤트를 수신해 user 동기화 스트림으로 전달합니다. X-DataGSM-Signature(HMAC-SHA256) 검증 후 처리합니다.
// @Tags         events
// @Accept       json
// @Produce      json
// @Param        X-DataGSM-Signature  header  string  true  "sha256=<HMAC-SHA256(secret, body)>"
// @Success      200  {object}  map[string]string  "수신 완료"
// @Failure      401  {object}  map[string]string  "서명 검증 실패"
// @Failure      503  {object}  map[string]string  "webhook secret 미설정"
// @Router       /events/datagsm [post]
func (h *EventHandler) DataGSMWebhook(c *gin.Context) {
	if !h.eventSvc.SecretConfigured() {
		c.JSON(http.StatusServiceUnavailable, gin.H{"error": "webhook is not configured"})
		return
	}

	body, err := io.ReadAll(io.LimitReader(c.Request.Body, maxWebhookBodyBytes))
	if err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "failed to read request body"})
		return
	}

	if !h.eventSvc.VerifySignature(body, c.GetHeader("X-DataGSM-Signature")) {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "invalid signature"})
		return
	}

	if err := h.eventSvc.ProcessEvent(c.Request.Context(), body); err != nil {
		// 처리 실패 시 500을 반환해 DataGSM 재시도를 유도한다.
		log.Printf("failed to process webhook event: %v", err)
		c.JSON(http.StatusInternalServerError, gin.H{"error": "failed to process event"})
		return
	}

	c.JSON(http.StatusOK, gin.H{"status": "received"})
}
