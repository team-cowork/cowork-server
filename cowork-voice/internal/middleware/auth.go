package middleware

import (
	"context"
	"net/http"
	"strconv"

	"github.com/cowork/cowork-voice/internal/apperr"
)

type contextKey string

const UserIDKey contextKey = "userID"

func ExtractAuthUser(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		raw := r.Header.Get("X-User-Id")
		if raw == "" {
			apperr.WriteResponse(w, apperr.Unauthorized())
			return
		}
		userID, err := strconv.ParseInt(raw, 10, 64)
		if err != nil {
			apperr.WriteResponse(w, apperr.Unauthorized())
			return
		}
		ctx := context.WithValue(r.Context(), UserIDKey, userID)
		next.ServeHTTP(w, r.WithContext(ctx))
	})
}

func GetUserID(ctx context.Context) (int64, bool) {
	userID, ok := ctx.Value(UserIDKey).(int64)
	return userID, ok
}
