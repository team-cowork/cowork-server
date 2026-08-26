package repository

import (
	"reflect"
	"testing"

	"github.com/cowork/authorization/internal/domain"
)

func TestUniqueSortedUserIDsDefinesDeterministicPresenceLockOrder(t *testing.T) {
	t.Parallel()

	tokens := []domain.RefreshToken{
		{UserID: 9},
		{UserID: 2},
		{UserID: 9},
		{UserID: 5},
	}

	if got, want := uniqueSortedUserIDs(tokens), []int64{2, 5, 9}; !reflect.DeepEqual(got, want) {
		t.Fatalf("uniqueSortedUserIDs() = %v, want deterministic lock order %v", got, want)
	}
}
