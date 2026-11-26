package handler

import (
	"errors"
	"log/slog"
	"net/http"
	"strconv"

	"github.com/gin-gonic/gin"

	"github.com/cowork/authorization/internal/service"
)

// AgentTokenHandler manages agent tokens. Gateway rejects agent JWTs on every route here,
// so only a login access JWT can issue, list or revoke them.
type AgentTokenHandler struct {
	agentTokenSvc *service.AgentTokenService
}

func NewAgentTokenHandler(agentTokenSvc *service.AgentTokenService) *AgentTokenHandler {
	return &AgentTokenHandler{agentTokenSvc: agentTokenSvc}
}

type CreateAgentTokenRequest struct {
	Name          string   `json:"name" binding:"required"`
	Scopes        []string `json:"scopes" binding:"required"`
	ExpiresInDays int      `json:"expires_in_days" binding:"required"`
}

// AgentTokenHeader carries the raw agent token on exchange. Gateway keys the exchange rate limit
// on its hash and strips it from every other route. Authorization stays free for access JWTs.
const AgentTokenHeader = "X-Cowork-Agent-Token"

func (h *AgentTokenHandler) Create(c *gin.Context) {
	c.Header("Cache-Control", "no-store")
	var req CreateAgentTokenRequest
	if c.ShouldBindJSON(&req) != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": "name, scopes and expires_in_days are required"})
		return
	}
	issued, err := h.agentTokenSvc.Issue(
		c.Request.Context(), c.GetInt64(userIDKey), req.Name, req.Scopes, req.ExpiresInDays,
	)
	if errors.Is(err, service.ErrInvalidAgentTokenRequest) {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid agent token request"})
		return
	}
	if errors.Is(err, service.ErrAgentTokenLimitExceeded) {
		c.JSON(http.StatusBadRequest, gin.H{"error": "too many active agent tokens; revoke one first"})
		return
	}
	if err != nil {
		slog.Error("Issue agent token", "err", err)
		c.JSON(http.StatusServiceUnavailable, gin.H{"error": "authentication temporarily unavailable"})
		return
	}
	c.JSON(http.StatusOK, issued)
}

func (h *AgentTokenHandler) List(c *gin.Context) {
	tokens, err := h.agentTokenSvc.List(c.Request.Context(), c.GetInt64(userIDKey))
	if err != nil {
		slog.Error("List agent tokens", "err", err)
		c.JSON(http.StatusServiceUnavailable, gin.H{"error": "authentication temporarily unavailable"})
		return
	}
	c.JSON(http.StatusOK, tokens)
}

func (h *AgentTokenHandler) Revoke(c *gin.Context) {
	id, err := strconv.ParseInt(c.Param("id"), 10, 64)
	if err != nil || id < 1 {
		c.JSON(http.StatusBadRequest, gin.H{"error": "invalid agent token id"})
		return
	}
	err = h.agentTokenSvc.Revoke(c.Request.Context(), c.GetInt64(userIDKey), id)
	if errors.Is(err, service.ErrAgentTokenNotFound) {
		c.JSON(http.StatusNotFound, gin.H{"error": "agent token not found"})
		return
	}
	if err != nil {
		slog.Error("Revoke agent token", "err", err)
		c.JSON(http.StatusServiceUnavailable, gin.H{"error": "authentication temporarily unavailable"})
		return
	}
	c.Status(http.StatusNoContent)
}

func (h *AgentTokenHandler) Exchange(c *gin.Context) {
	c.Header("Cache-Control", "no-store")
	token := c.GetHeader(AgentTokenHeader)
	if token == "" {
		c.JSON(http.StatusBadRequest, gin.H{"error": AgentTokenHeader + " header is required"})
		return
	}
	access, err := h.agentTokenSvc.Exchange(c.Request.Context(), token)
	if errors.Is(err, service.ErrAgentTokenRejected) {
		c.JSON(http.StatusUnauthorized, gin.H{"error": "invalid, expired or revoked agent token"})
		return
	}
	if err != nil {
		slog.Error("Exchange agent token", "err", err)
		c.JSON(http.StatusServiceUnavailable, gin.H{"error": "authentication temporarily unavailable"})
		return
	}
	c.JSON(http.StatusOK, access)
}
