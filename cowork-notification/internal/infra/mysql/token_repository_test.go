package mysql_test

import (
	"testing"

	"github.com/cowork/cowork-notification/internal/domain/token"
	"github.com/cowork/cowork-notification/internal/infra/mysql"
)

func TestTokenRepository_ImplementsInterface(t *testing.T) {
	var _ token.Repository = (*mysql.TokenRepository)(nil)
}
