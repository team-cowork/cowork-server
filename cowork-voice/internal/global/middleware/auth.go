package middleware

import (
	"context"
	"net/http"
	"strconv"

	"github.com/cowork/cowork-voice/internal/global/apperror"
)

type contextKey string

const UserIDKey contextKey = "userID"

func ExtractAuthUser(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		raw := r.Header.Get("X-User-Id")
		if raw == "" {
			apperror.WriteResponse(w, apperror.Unauthorized())
			return
		}
		userID, err := strconv.ParseInt(raw, 10, 64)
		if err != nil {
			apperror.WriteResponse(w, apperror.Unauthorized())
			return
		}
		ctx := context.WithValue(r.Context(), UserIDKey, userID)
		next.ServeHTTP(w, r.WithContext(ctx))
	})
}

func GetUserID(ctx context.Context) int64 {
	return ctx.Value(UserIDKey).(int64)
}
