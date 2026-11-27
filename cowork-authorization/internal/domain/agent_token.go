package domain

import (
	"errors"
	"slices"
	"strings"
	"time"
)

// AgentTokenUse is the token_use claim of an access JWT exchanged from an agent token.
// Gateway restricts such JWTs to the allowlist of their scopes.
const AgentTokenUse = "agent"

// AgentScopes must match the scope allowlist in Gateway.
var AgentScopes = []string{"workspace:read", "chat:read", "chat:write"}

var ErrInvalidAgentScope = errors.New("invalid agent token scope")

type AgentToken struct {
	ID         int64      `gorm:"primaryKey;autoIncrement;column:id"`
	UserID     int64      `gorm:"column:user_id;not null"`
	Name       string     `gorm:"size:100;column:name;not null"`
	TokenHash  string     `gorm:"type:char(64) CHARACTER SET ascii COLLATE ascii_bin;column:token_hash;not null"`
	Scopes     string     `gorm:"size:255;column:scopes;not null"`
	ExpiresAt  time.Time  `gorm:"column:expires_at;not null"`
	LastUsedAt *time.Time `gorm:"column:last_used_at"`
	RevokedAt  *time.Time `gorm:"column:revoked_at"`
	CreatedAt  time.Time  `gorm:"column:created_at;autoCreateTime:nano"`
}

func (AgentToken) TableName() string {
	return "tb_agent_tokens"
}

// Exchangeable reports whether the token can still be exchanged for an access JWT.
func (t AgentToken) Exchangeable(at time.Time) bool {
	return t.RevokedAt == nil && t.ExpiresAt.After(at)
}

// NormalizeAgentScopes rejects empty or unknown scopes and returns the rest sorted without duplicates.
func NormalizeAgentScopes(scopes []string) ([]string, error) {
	if len(scopes) == 0 {
		return nil, ErrInvalidAgentScope
	}
	normalized := make([]string, 0, len(scopes))
	for _, scope := range scopes {
		if !slices.Contains(AgentScopes, scope) {
			return nil, ErrInvalidAgentScope
		}
		normalized = append(normalized, scope)
	}
	slices.Sort(normalized)
	return slices.Compact(normalized), nil
}

// JoinAgentScopes formats scopes as the space-separated scope claim.
func JoinAgentScopes(scopes []string) string {
	return strings.Join(scopes, " ")
}
