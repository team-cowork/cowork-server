package mysql

import (
	"context"
	"testing"

	"github.com/cowork/cowork-notification/internal/domain/token"
)

// These tests cover the pure guard-clause logic in TokenRepository that is
// reachable without a live database connection: empty-input short-circuits
// that return before the method ever touches r.db. The repository is
// constructed with a nil *gorm.DB to prove these paths never dereference it;
// any regression that removes the early return would panic here instead of
// silently requiring a real DB in production.

func TestTokenRepository_FindByAccountIDs_emptyInput(t *testing.T) {
	tests := []struct {
		name       string
		accountIDs []int64
	}{
		{name: "nil slice", accountIDs: nil},
		{name: "empty slice", accountIDs: []int64{}},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			repo := &TokenRepository{db: nil}

			got, err := repo.FindByAccountIDs(context.Background(), tt.accountIDs)

			if err != nil {
				t.Fatalf("FindByAccountIDs() error = %v, want nil", err)
			}
			if got != nil {
				t.Fatalf("FindByAccountIDs() = %v, want nil map", got)
			}
		})
	}
}

func TestTokenRepository_DeleteByTokens_emptyInput(t *testing.T) {
	tests := []struct {
		name   string
		tokens []string
	}{
		{name: "nil slice", tokens: nil},
		{name: "empty slice", tokens: []string{}},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			repo := &TokenRepository{db: nil}

			err := repo.DeleteByTokens(context.Background(), tt.tokens)

			if err != nil {
				t.Fatalf("DeleteByTokens() error = %v, want nil", err)
			}
		})
	}
}

// TestTokenRepository_ImplementsRepositoryInterface guards against silent
// interface drift. Unlike the deleted assertion-free version of this test,
// it is kept alongside real behavioral coverage above rather than standing
// in for it.
func TestTokenRepository_ImplementsRepositoryInterface(t *testing.T) {
	var _ token.Repository = (*TokenRepository)(nil)
}
